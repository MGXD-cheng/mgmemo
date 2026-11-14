package com.mgmemo.app

import com.mgmemo.app.data.HtmlRenderer
import com.mgmemo.app.data.MarkdownWriter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 预览渲染测试：确认渲染不依赖外部脚本（这是「一直白屏」的根因）。
 */
class RenderPipelineTest {

    // ==================== Markdown → HTML ====================

    @Test
    fun `标题加粗表格列表都被渲染`() {
        val md = """
            # 标题

            这是**加粗**文本

            - 条目

            | A | B |
            | --- | --- |
            | 1 | 2 |
        """.trimIndent()
        val html = HtmlRenderer.renderBody(md)
        assertTrue("h1", html.contains("<h1>标题</h1>"))
        assertTrue("strong", html.contains("<strong>加粗</strong>"))
        assertTrue("列表", html.contains("• "))
        assertTrue("表格", html.contains("<table>"))
        assertTrue("表头", html.contains("<th>A</th>"))
    }

    @Test
    fun `代码块保留语言并转义尖括号`() {
        val md = "```kotlin\nval a = List<String>()\n```"
        val html = HtmlRenderer.renderBody(md)
        assertTrue(html.contains("language-kotlin"))
        assertTrue("代码内容应转义", html.contains("List&lt;String&gt;"))
    }

    @Test
    fun `mermaid代码块用专门容器`() {
        val html = HtmlRenderer.renderBody("```mermaid\ngraph TD\nA-->B\n```")
        assertTrue(html.contains("class=\"mermaid\""))
    }

    @Test
    fun `整篇HTML笔记渲染为正文而非源码`() {
        val source = """<!DOCTYPE html><html><body><h2>演示</h2><p>正文内容</p></body></html>"""
        val html = HtmlRenderer.renderBody(source)
        assertTrue("标题应渲染", html.contains("<h2>演示</h2>"))
        assertTrue("正文应渲染", html.contains("正文内容"))
        assertFalse("不应出现 DOCTYPE 源码", html.contains("DOCTYPE"))
        assertFalse("不应把 html 标签当文本转义", html.contains("&lt;html"))
    }

    @Test
    fun `图片输出img标签且保留data url`() {
        val html = HtmlRenderer.renderBody("![](data:image/jpeg;base64,AAAA)")
        assertTrue(html.contains("<img src=\"data:image/jpeg;base64,AAAA\""))
    }

    // ==================== 首屏不依赖网络 ====================

    @Test
    fun `首屏文档不含任何网络地址`() {
        val doc = HtmlRenderer.document("<p>内容</p>", "dark")
        assertTrue("正文应内联", doc.contains("<p>内容</p>"))
        assertFalse(
            "不应引用任何远程地址（否则离线/被墙时会白屏）",
            doc.contains("http://") || doc.contains("https://")
        )
        assertFalse(
            "不应有阻塞式外部脚本",
            doc.contains("<script src=")
        )
        assertTrue("深色主题类名", doc.contains("theme-dark"))
        assertTrue("应引用本地高亮样式", doc.contains("href=\"github.min.css\""))
        assertTrue("应引用本地公式样式", doc.contains("href=\"katex.min.css\""))
    }

    @Test
    fun `主题类名正确映射`() {
        assertTrue(HtmlRenderer.document("", "light").contains("theme-light"))
        assertTrue(HtmlRenderer.document("", "green").contains("theme-green"))
        assertTrue(HtmlRenderer.document("", "dark").contains("theme-dark"))
    }

    // ==================== HTML → Markdown（所见即所得回写） ====================

    @Test
    fun `缺少分隔行的竖线表格也能渲染`() {
        val md = "| 规则 | 要求 |\n| 规则1 | 不碰手机 |\n| 规则2 | 限时使用 |"
        val html = HtmlRenderer.renderBody(md)
        assertTrue("应渲染为表格", html.contains("<table>"))
        assertTrue("表头", html.contains("<th>规则</th>"))
        assertTrue("数据行", html.contains("<td>规则1</td>"))
    }

    @Test
    fun `省略首尾竖线的表格也能识别`() {
        val html = HtmlRenderer.renderBody("A | B\n1 | 2")
        assertTrue(html.contains("<table>"))
        assertTrue(html.contains("<td>1</td>"))
    }

    @Test
    fun `制表符表格被识别`() {
        val md = "时间\t事项\t备注\n8:00\t起床\t不碰手机"
        val html = HtmlRenderer.renderBody(md)
        assertTrue("应渲染为表格", html.contains("<table>"))
        assertTrue("表头", html.contains("<th>时间</th>"))
        assertTrue("数据", html.contains("<td>8:00</td>"))
    }

    @Test
    fun `列数不一致时自动补齐`() {
        val md = "| A | B | C |\n| 1 | 2 |"
        val table = com.mgmemo.app.data.DocParser.parse(md)
            .filterIsInstance<com.mgmemo.app.data.DocBlock.Table>()
            .first()
        assertTrue("表头应有3列", table.header.size == 3)
        assertTrue("数据行应补齐到3列", table.rows.first().size == 3)
    }

    @Test
    fun `标题与列表里的竖线不会被误判为表格`() {
        assertFalse(HtmlRenderer.renderBody("## 方案 | 备注").contains("<table>"))
        assertFalse(HtmlRenderer.renderBody("- 甲 | 乙").contains("<table>"))
    }

    @Test
    fun `HTML转回Markdown保留结构`() {
        val html = "<h1>标题</h1><p>正文<strong>粗</strong>与<em>斜</em></p><ul><li>条目</li></ul>"
        val md = MarkdownWriter.fromHtml(html)
        assertTrue("标题", md.contains("# 标题"))
        assertTrue("加粗", md.contains("**粗**"))
        assertTrue("斜体", md.contains("*斜*"))
        assertTrue("列表", md.contains("- 条目"))
    }

    @Test
    fun `HTML转Markdown处理表格与代码`() {
        val html = "<table><tr><th>A</th></tr><tr><td>1</td></tr></table>" +
            "<pre><code class=\"language-kotlin\">val a = 1</code></pre>"
        val md = MarkdownWriter.fromHtml(html)
        assertTrue("表格", md.contains("| A |"))
        assertTrue("代码围栏语言", md.contains("```kotlin"))
        assertTrue("代码内容", md.contains("val a = 1"))
    }

    @Test
    fun `空输入不会崩溃`() {
        assertTrue(HtmlRenderer.renderBody("").isEmpty())
        assertTrue(MarkdownWriter.fromHtml("").isEmpty())
        assertTrue(MarkdownWriter.fromHtml("<p></p>").isBlank())
    }
}