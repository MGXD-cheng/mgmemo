package com.mgmemo.app

import com.mgmemo.app.data.DocBlock
import com.mgmemo.app.data.DocParser
import com.mgmemo.app.data.DocRun
import com.mgmemo.app.data.ExportUtils
import com.mgmemo.app.data.ImageInfo
import com.mgmemo.app.data.WordWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * 导出管线的纯 JVM 测试：
 * Markdown / HTML → 文档模型 → WordprocessingML / 纯文本。
 * （PDF 走原生 Canvas，需真机；此处验证其输入模型是否正确。）
 */
class ExportPipelineTest {

    // ==================== 文档模型 ====================

    @Test
    fun `标题与段落被解析`() {
        val blocks = DocParser.parse("# 一级标题\n\n这是正文")
        assertEquals(2, blocks.size)
        val h = blocks[0] as DocBlock.Heading
        assertEquals(1, h.level)
        assertEquals("一级标题", h.runs.joinToString("") { it.text })
        val p = blocks[1] as DocBlock.Paragraph
        assertEquals("这是正文", p.runs.joinToString("") { it.text })
    }

    @Test
    fun `加粗与斜体生成带样式的片段`() {
        val blocks = DocParser.parse("这是**加粗**和*斜体*")
        val p = blocks.first() as DocBlock.Paragraph
        val bold = p.runs.first { it.text.contains("加粗") }
        val italic = p.runs.first { it.text.contains("斜体") }
        assertTrue("加粗片段应为 bold", bold.bold)
        assertTrue("斜体片段应为 italic", italic.italic)
    }

    @Test
    fun `表格被解析为行列`() {
        val md = """
            | 名称 | 数量 |
            | --- | --- |
            | 苹果 | 3 |
        """.trimIndent()
        val t = DocParser.parse(md).first() as DocBlock.Table
        assertEquals(listOf("名称", "数量"), t.header)
        assertEquals(1, t.rows.size)
        assertEquals(listOf("苹果", "3"), t.rows[0])
    }

    @Test
    fun `列表与引用被解析`() {
        val blocks = DocParser.parse("- 项目一\n\n> 引用内容")
        val bullet = blocks[0] as DocBlock.Paragraph
        assertTrue(bullet.bullet)
        val quote = blocks[1] as DocBlock.Quote
        assertEquals("引用内容", quote.runs.joinToString("") { it.text })
    }

    @Test
    fun `代码块被解析为多行`() {
        val md = "```kotlin\nval a = 1\nval b = 2\n```"
        val code = DocParser.parse(md).first() as DocBlock.Code
        assertEquals(listOf("val a = 1", "val b = 2"), code.lines)
    }

    @Test
    fun `整篇 HTML 文档被结构化解析`() {
        val html = """
            <!DOCTYPE html>
            <html><head><title>演示</title><style>body{color:red}</style></head>
            <body>
            <h1>Magic Note</h1>
            <p>正文<b>加粗</b>内容</p>
            <ul><li>条目一</li></ul>
            </body></html>
        """.trimIndent()
        val blocks = DocParser.parse(html)

        val heading = blocks.filterIsInstance<DocBlock.Heading>().first()
        assertEquals("Magic Note", heading.runs.joinToString("") { it.text })

        val texts = blocks.filterIsInstance<DocBlock.Paragraph>()
            .joinToString("|") { p -> p.runs.joinToString("") { it.text } }
        assertTrue("正文应保留", texts.contains("正文"))
        assertTrue("条目应保留", texts.contains("条目一"))
        // style / title 内的样式定义不应出现在正文
        assertFalse("不应残留 CSS", texts.contains("color:red"))
    }

    @Test
    fun `行内 HTML 标签转为样式而非文字`() {
        val blocks = DocParser.parse("文字 <strong>重要</strong> 结束")
        val p = blocks.first() as DocBlock.Paragraph
        val text = p.runs.joinToString("") { it.text }
        assertTrue(text.contains("重要"))
        assertFalse("标签本身不应出现在文本里", text.contains("<strong>"))
        assertTrue("应保留加粗样式", p.runs.any { it.bold && it.text.contains("重要") })
    }

    @Test
    fun `链接丢弃地址保留文字`() {
        val blocks = DocParser.parse("[百度](https://www.baidu.com)")
        val p = blocks.first() as DocBlock.Paragraph
        val text = p.runs.joinToString("") { it.text }
        assertTrue(text.contains("百度"))
        assertFalse(text.contains("baidu.com"))
    }

    @Test
    fun `图片被解析为独立块且不含 base64 文本`() {
        val md = "![](data:image/jpeg;base64,AAAA////BBBB)"
        val blocks = DocParser.parse(md)
        assertTrue("应生成图片块", blocks.first() is DocBlock.Image)
    }

    // ==================== Word（WordprocessingML） ====================

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val map = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val content = zin.readBytes().toString(Charsets.UTF_8)
                map[entry.name] = content
                entry = zin.nextEntry
            }
        }
        return map
    }

    @Test
    fun `生成的 docx 是合法包且含正文样式`() {
        val blocks = listOf(
            DocBlock.Heading(1, listOf(DocRun("标题", bold = true))),
            DocBlock.Paragraph(listOf(DocRun("普通正文"), DocRun("加粗字", bold = true))),
            DocBlock.Table(listOf("列A", "列B"), listOf(listOf("1", "2"))),
            DocBlock.Code(listOf("val a = 1"))
        )
        val bos = ByteArrayOutputStream()
        WordWriter.build(bos, blocks)
        val parts = unzip(bos.toByteArray())

        assertTrue("含内容类型声明", parts.containsKey("[Content_Types].xml"))
        assertTrue("含根关系", parts.containsKey("_rels/.rels"))
        assertTrue("含文档主体", parts.containsKey("word/document.xml"))
        assertTrue("含关系文件", parts.containsKey("word/_rels/document.xml.rels"))

        val doc = parts["word/document.xml"]!!
        assertTrue("应包含加粗标记", doc.contains("<w:b/>"))
        assertTrue("应包含段落", doc.contains("<w:p>"))
        assertTrue("应包含表格", doc.contains("<w:tbl>"))
        assertTrue("应包含字号设置", doc.contains("<w:sz w:val="))
        assertTrue("正文文字应存在", doc.contains("普通正文"))
        assertTrue("表格文字应存在", doc.contains("列A"))
    }

    @Test
    fun `Word 输出转义 XML 特殊字符`() {
        val blocks = listOf(DocBlock.Paragraph(listOf(DocRun("a < b & c > d"))))
        val bos = ByteArrayOutputStream()
        WordWriter.build(bos, blocks)
        val doc = unzip(bos.toByteArray())["word/document.xml"]!!
        assertTrue(doc.contains("a " + "&" + "lt; b"))
        assertTrue(doc.contains("&" + "amp;"))
        assertFalse("不应出现裸标签", doc.contains("<w:t>a < b"))
    }

    // ==================== 纯文本 ====================

    @Test
    fun `纯文本剥离全部标记符号`() {
        val md = """
            # 标题

            **加粗**、*斜体*、~~删除~~、`代码`

            - 项目一

            > 引用

            [链接文字](https://example.com)
        """.trimIndent()
        val plain = ExportUtils.markdownToPlainText(md)

        assertFalse("不应残留井号", plain.contains("#"))
        assertFalse("不应残留星号", plain.contains("*"))
        assertFalse("不应残留波浪线", plain.contains("~~"))
        assertFalse("不应残留反引号", plain.contains("`"))
        assertFalse("不应残留链接地址", plain.contains("example.com"))
        assertTrue("标题应保留", plain.contains("标题"))
        assertTrue("加粗文字应保留", plain.contains("加粗"))
        assertTrue("链接文字应保留", plain.contains("链接文字"))
        assertTrue("列表应带圆点", plain.contains("•"))
    }

    @Test
    fun `纯文本去除 Base64 图片数据`() {
        val plain = ExportUtils.markdownToPlainText("前面![](data:image/jpeg;base64,AAAA////BBBB)后面")
        assertFalse("不应包含 base64 数据", plain.contains("////"))
        assertTrue(plain.contains("前面"))
    }

    @Test
    fun `纯文本渲染 HTML 内容而非源码`() {
        val plain = ExportUtils.markdownToPlainText(
            """<!DOCTYPE html><html><body><h2>标题二</h2><p>段落文字</p></body></html>"""
        )
        assertTrue(plain.contains("标题二"))
        assertTrue(plain.contains("段落文字"))
        assertFalse(plain.contains("DOCTYPE"))
        assertFalse(plain.contains("<p>"))
    }

    // ==================== 图片尺寸解析 ====================

    @Test
    fun `PNG 尺寸可被解析`() {
        // 最小 PNG 头：宽 3 高 5
        val png = ByteArray(33)
        png[0] = 0x89.toByte(); png[1] = 'P'.code.toByte()
        png[2] = 'N'.code.toByte(); png[3] = 'G'.code.toByte()
        png[16] = 0; png[17] = 0; png[18] = 0; png[19] = 3
        png[20] = 0; png[21] = 0; png[22] = 0; png[23] = 5
        assertEquals(3 to 5, ImageInfo.size(png))
    }

    @Test
    fun `非图片数据返回 null`() {
        assertEquals(null, ImageInfo.size(byteArrayOf(1, 2, 3, 4)))
    }
}