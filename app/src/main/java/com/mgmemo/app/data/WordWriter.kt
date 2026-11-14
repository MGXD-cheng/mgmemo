package com.mgmemo.app.data

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 真正的 WordprocessingML 写出器（不使用 aFChunk）：
 * 直接把文档块翻译成 <w:p> / <w:r> / <w:tbl> / <w:drawing>，
 * 因此 Word / WPS / Google Docs 都能正确显示字号、加粗、表格与图片。
 */
object WordWriter {

    // 字号（半磅）：22 = 11pt
    private const val SZ_H1 = 44
    private const val SZ_H2 = 36
    private const val SZ_H3 = 32
    private const val SZ_H4 = 28
    private const val SZ_H5 = 24
    private const val SZ_BODY = 22
    private const val SZ_CODE = 20

    /** 页面内容宽度（twips，A4 去掉左右各 1 英寸边距） */
    private const val CONTENT_TWIPS = 9026

    /** 图片最大宽度（EMU，1pt = 12700 EMU） */
    private const val MAX_IMAGE_EMU = 451L * 12700L

    private const val NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_WP = "http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"
    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_PIC = "http://schemas.openxmlformats.org/drawingml/2006/picture"

    fun build(out: OutputStream, blocks: List<DocBlock>, docTitle: String = "") {
        val media = mutableListOf<Media>()

        val body = StringBuilder()
        if (docTitle.isNotBlank()) {
            body.append(
                para(
                    listOf(DocRun(docTitle, bold = true)),
                    SZ_H1, centered = true, border = true
                )
            )
        }
        blocks.forEach { block -> appendBlock(body, block, media) }
        body.append(
            """<w:sectPr><w:pgSz w:w="11906" w:h="16838"/>""" +
                """<w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440" """ +
                """w:header="851" w:footer="992" w:gutter="0"/></w:sectPr>"""
        )

        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="$NS" xmlns:r="$NS_R" xmlns:wp="$NS_WP" xmlns:a="$NS_A" xmlns:pic="$NS_PIC">
<w:body>$body</w:body>
</w:document>"""

        val contentTypes = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
            append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""")
            append("""<Default Extension="xml" ContentType="application/xml"/>""")
            append("""<Default Extension="png" ContentType="image/png"/>""")
            append("""<Default Extension="jpg" ContentType="image/jpeg"/>""")
            append("""<Default Extension="jpeg" ContentType="image/jpeg"/>""")
            append("""<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""")
            append("""</Types>""")
        }

        val rootRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""

        val docRels = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""")
            media.forEach { m ->
                append("""<Relationship Id="${m.relId}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/${m.fileName}"/>""")
            }
            append("""</Relationships>""")
        }

        ZipOutputStream(out).use { zip ->
            zip.put("[Content_Types].xml", contentTypes)
            zip.put("_rels/.rels", rootRels)
            zip.put("word/document.xml", documentXml)
            zip.put("word/_rels/document.xml.rels", docRels)
            media.forEach { m -> zip.put("word/media/${m.fileName}", null, m.bytes) }
        }
    }

    private fun ZipOutputStream.put(name: String, text: String) =
        put(name, text, null)

    private fun ZipOutputStream.put(name: String, text: String?, bytes: ByteArray?) {
        putNextEntry(ZipEntry(name))
        if (bytes != null) write(bytes) else write((text ?: "").toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    // ==================== 块渲染 ====================

    private class Media(val relId: String, val fileName: String, val bytes: ByteArray)

    private fun appendBlock(sb: StringBuilder, block: DocBlock, media: MutableList<Media>) {
        when (block) {
            is DocBlock.Heading -> {
                val size = when (block.level) {
                    1 -> SZ_H1
                    2 -> SZ_H2
                    3 -> SZ_H3
                    4 -> SZ_H4
                    else -> SZ_H5
                }
                val runs = block.runs.map { it.copy(bold = true) }
                sb.append(para(runs, size, border = block.level <= 2))
            }

            is DocBlock.Paragraph -> {
                val prefix = when {
                    block.bullet -> "• "
                    block.ordered != null -> "${block.ordered}. "
                    else -> ""
                }
                val runs = if (prefix.isEmpty()) block.runs
                else listOf(DocRun(prefix)) + block.runs
                val indentTwips = if (prefix.isEmpty()) null else 360 + block.indent * 360
                sb.append(para(runs, SZ_BODY, indent = indentTwips))
            }

            is DocBlock.Quote -> {
                sb.append(
                    para(
                        block.runs.map { it.copy(italic = true) },
                        SZ_BODY,
                        indent = 360,
                        leftBorder = true
                    )
                )
            }

            is DocBlock.Code -> {
                block.lines.forEach { line ->
                    sb.append(
                        para(
                            listOf(DocRun(if (line.isEmpty()) " " else line, code = true)),
                            SZ_CODE,
                            shaded = true,
                            indent = 240
                        )
                    )
                }
            }

            is DocBlock.Table -> {
                sb.append(table(block))
            }

            is DocBlock.Image -> {
                val bytes = block.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    sb.append(para(listOf(DocRun("[图片] ${block.alt}")), SZ_BODY))
                } else {
                    val index = media.size + 1
                    val ext = extOf(bytes)
                    val fileName = "image$index.$ext"
                    val relId = "rIdImg$index"
                    media.add(Media(relId, fileName, bytes))
                    sb.append(imageParagraph(bytes, relId, index))
                }
            }

            DocBlock.Divider -> sb.append(
                para(listOf(DocRun("")), SZ_BODY, border = true)
            )
        }
    }

    private fun para(
        runs: List<DocRun>,
        size: Int,
        centered: Boolean = false,
        indent: Int? = null,
        border: Boolean = false,
        leftBorder: Boolean = false,
        shaded: Boolean = false
    ): String {
        val sb = StringBuilder("<w:p><w:pPr>")
        sb.append("""<w:spacing w:before="80" w:after="80" w:line="300" w:lineRule="auto"/>""")
        if (centered) sb.append("""<w:jc w:val="center"/>""")
        if (indent != null) sb.append("""<w:ind w:left="$indent"/>""")
        if (shaded) sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="F2F4F7"/>""")
        if (border || leftBorder) {
            sb.append("<w:pBdr>")
            if (border) {
                sb.append("""<w:bottom w:val="single" w:sz="6" w:space="2" w:color="D0D7DE"/>""")
            }
            if (leftBorder) {
                sb.append("""<w:left w:val="single" w:sz="18" w:space="8" w:color="C2C9BC"/>""")
            }
            sb.append("</w:pBdr>")
        }
        sb.append("</w:pPr>")

        if (runs.isEmpty()) {
            sb.append("""<w:r><w:rPr>${rPr(size, false, false, false, false)}</w:rPr><w:t xml:space="preserve"> </w:t></w:r>""")
        } else {
            runs.forEach { r ->
                if (r.lineBreakBefore) sb.append("<w:r><w:br/></w:r>")
                sb.append("<w:r><w:rPr>")
                sb.append(rPr(size, r.bold, r.italic, r.strike, r.code))
                sb.append("</w:rPr><w:t xml:space=\"preserve\">")
                sb.append(escape(r.text))
                sb.append("</w:t></w:r>")
            }
        }
        sb.append("</w:p>")
        return sb.toString()
    }

    private fun rPr(size: Int, bold: Boolean, italic: Boolean, strike: Boolean, code: Boolean): String {
        val sb = StringBuilder()
        if (code) {
            sb.append("""<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/>""")
        }
        if (bold) sb.append("<w:b/><w:bCs/>")
        if (italic) sb.append("<w:i/><w:iCs/>")
        if (strike) sb.append("<w:strike/>")
        sb.append("""<w:sz w:val="$size"/><w:szCs w:val="$size"/>""")
        return sb.toString()
    }

    private fun table(t: DocBlock.Table): String {
        val cols = maxOf(t.header.size, t.rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
        val colWidth = CONTENT_TWIPS / cols
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr>")
        sb.append("""<w:tblW w:w="$CONTENT_TWIPS" w:type="dxa"/>""")
        sb.append("<w:tblBorders>")
        listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { edge ->
            sb.append("""<w:$edge w:val="single" w:sz="4" w:space="0" w:color="D0D7DE"/>""")
        }
        sb.append("</w:tblBorders></w:tblPr>")

        sb.append("<w:tr>")
        t.header.forEach { cell ->
            sb.append("""<w:tc><w:tcPr><w:tcW w:w="$colWidth" w:type="dxa"/>""")
            sb.append("""<w:shd w:val="clear" w:color="auto" w:fill="F0F3F6"/></w:tcPr>""")
            sb.append(para(listOf(DocRun(cell, bold = true)), SZ_BODY))
            sb.append("</w:tc>")
        }
        sb.append("</w:tr>")

        t.rows.forEach { row ->
            sb.append("<w:tr>")
            row.forEach { cell ->
                sb.append("""<w:tc><w:tcPr><w:tcW w:w="$colWidth" w:type="dxa"/></w:tcPr>""")
                sb.append(para(DocParser.parseInline(cell), SZ_BODY))
                sb.append("</w:tc>")
            }
            sb.append("</w:tr>")
        }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private fun imageParagraph(bytes: ByteArray, relId: String, index: Int): String {
        val size = ImageInfo.size(bytes)
        var cx = 400L * 12700L
        var cy = 300L * 12700L
        if (size != null && size.first > 0 && size.second > 0) {
            val ratio = size.second.toDouble() / size.first.toDouble()
            if (cx > MAX_IMAGE_EMU) cx = MAX_IMAGE_EMU
            cy = (cx * ratio).toLong()
        }
        return """<w:p><w:pPr><w:jc w:val="center"/></w:pPr><w:r><w:drawing>
<wp:inline distT="0" distB="0" distL="0" distR="0">
<wp:extent cx="$cx" cy="$cy"/>
<wp:docPr id="$index" name="Image$index"/>
<a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">
<pic:pic><pic:nvPicPr><pic:cNvPr id="$index" name="image$index"/><pic:cNvPicPr/></pic:nvPicPr>
<pic:blipFill><a:blip r:embed="$relId"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>
<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm>
<a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>
</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>"""
    }

    private fun extOf(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() -> "png"
        else -> "jpg"
    }

    private fun escape(s: String): String = s
        .replace("&", "&" + "amp;")
        .replace("<", "&" + "lt;")
        .replace(">", "&" + "gt;")
        .replace("\"", "&" + "quot;")
}