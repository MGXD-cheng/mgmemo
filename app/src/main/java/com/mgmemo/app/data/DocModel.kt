package com.mgmemo.app.data

import android.util.Base64

/**
 * 统一文档模型：Markdown / HTML 先解析成结构化的块与行内片段，
 * 再分别交给 PdfWriter（Canvas 绘制）与 WordWriter（WordprocessingML）渲染。
 *
 * 这样导出的 PDF / Word 内容一定与笔记一致，且不依赖 WebView 的渲染时机。
 */

/** 行内片段（带样式） */
data class DocRun(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    /** 该片段之前需要强制换行（对应 <br>） */
    val lineBreakBefore: Boolean = false
)

sealed interface DocBlock {
    data class Heading(val level: Int, val runs: List<DocRun>) : DocBlock

    data class Paragraph(
        val runs: List<DocRun>,
        /** 无序列表项（前缀 •） */
        val bullet: Boolean = false,
        /** 有序列表序号（从 1 开始），null 表示普通段落 */
        val ordered: Int? = null,
        /** 列表缩进层级 */
        val indent: Int = 0
    ) : DocBlock

    data class Quote(val runs: List<DocRun>) : DocBlock

    data class Code(val lines: List<String>, val lang: String = "") : DocBlock

    data class Table(val header: List<String>, val rows: List<List<String>>) : DocBlock

    /**
     * 图片：url 可能是 data:image/base64 数据或普通链接。
     * 字节按需解码——每次渲染都解码大图会明显卡顿。
     */
    class Image(val url: String, val alt: String = "") : DocBlock {
        fun bytes(): ByteArray? {
            if (!url.startsWith("data:image", ignoreCase = true)) return null
            val b64 = url.substringAfter("base64,", "")
            if (b64.isBlank()) return null
            return try {
                Base64.decode(b64, Base64.DEFAULT)
            } catch (e: Exception) {
                null
            }
        }
    }

    data object Divider : DocBlock
}

object DocParser {

    private val VOID_TAGS = setOf(
        "br", "hr", "img", "input", "meta", "link",
        "area", "base", "col", "embed", "source", "track", "wbr"
    )

    /** 入口：自动识别 Markdown 或 HTML 文档 */
    fun parse(source: String): List<DocBlock> {
        val text = source.trim()
        if (text.isEmpty()) return emptyList()
        return if (looksLikeHtml(text)) parseHtml(text) else parseMarkdown(text)
    }

    /** 强制按 HTML 解析（所见即所得编辑器回传的 innerHTML 用这个） */
    fun parseHtmlFragment(html: String): List<DocBlock> {
        if (html.isBlank()) return emptyList()
        return parseHtml(html)
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.trimStart()
        return head.startsWith("<!DOCTYPE", ignoreCase = true) ||
            head.startsWith("<html", ignoreCase = true)
    }

    // ==================== Markdown ====================

    private fun parseMarkdown(md: String): List<DocBlock> {
        val blocks = mutableListOf<DocBlock>()
        val lines = md.lines()
        var i = 0
        var quoteBuf: MutableList<String>? = null

        fun flushQuote() {
            quoteBuf?.let { buf ->
                if (buf.isNotEmpty()) {
                    blocks.add(DocBlock.Quote(parseInline(buf.joinToString(" "))))
                }
            }
            quoteBuf = null
        }

        while (i < lines.size) {
            val raw = lines[i]
            val line = raw.trim()

            // ---- 代码块 ----
            if (line.startsWith("```")) {
                flushQuote()
                val lang = line.removePrefix("```").trim().substringBefore(' ')
                val buf = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trim().startsWith("```")) {
                    buf.add(lines[i])
                    i++
                }
                i++ // 跳过结束围栏
                blocks.add(DocBlock.Code(buf, lang))
                continue
            }

            // ---- 表格 ----
            if (isTableRow(line)) {
                // 标准 GFM 有 --- 分隔行；很多 AI/复制粘贴会丢掉它，这里两种都接受
                val hasSeparator = i + 1 < lines.size && isSeparatorRow(lines[i + 1].trim())
                val nextIsRow = i + 1 < lines.size && isTableRow(lines[i + 1].trim())
                if (hasSeparator || nextIsRow) {
                    flushQuote()
                    val header = splitCells(line)
                    var j = if (hasSeparator) i + 2 else i + 1
                    val body = mutableListOf<List<String>>()
                    while (j < lines.size && isTableRow(lines[j].trim())) {
                        body.add(splitCells(lines[j].trim()))
                        j++
                    }
                    blocks.add(normalizeTable(header, body))
                    i = j
                    continue
                }
            }

            // ---- 制表符表格（从 Excel / 表格类应用粘贴常见此格式） ----
            if (line.contains('\t') && line.count { it == '\t' } >= 1) {
                val next = if (i + 1 < lines.size) lines[i + 1].trim() else ""
                if (next.contains('\t')) {
                    flushQuote()
                    val header = line.split('\t').map { it.trim() }
                    val body = mutableListOf<List<String>>()
                    var j = i + 1
                    while (j < lines.size && lines[j].trim().contains('\t')) {
                        body.add(lines[j].trim().split('\t').map { it.trim() })
                        j++
                    }
                    blocks.add(normalizeTable(header, body))
                    i = j
                    continue
                }
            }

            when {
                line.isEmpty() -> flushQuote()

                line.matches(Regex("""^([-*_]\s*){3,}$""")) -> {
                    flushQuote()
                    blocks.add(DocBlock.Divider)
                }

                line.startsWith("#") -> {
                    flushQuote()
                    val level = line.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val content = line.drop(level).trim()
                    if (content.isEmpty()) blocks.add(DocBlock.Divider)
                    else blocks.add(DocBlock.Heading(level, parseInline(content)))
                }

                line.startsWith(">") -> {
                    if (quoteBuf == null) quoteBuf = mutableListOf()
                    quoteBuf!!.add(line.drop(1).trim())
                }

                // 独占一行的图片
                IMAGE_ONLY.matches(line) -> {
                    flushQuote()
                    blocks.add(parseImageBlock(line))
                }

                line.matches(Regex("""^[-*+]\s*\[[ xX]\]\s+.*""")) -> {
                    flushQuote()
                    val checked = line[line.indexOf('[') + 1].let { it == 'x' || it == 'X' }
                    val text = line.substringAfter(']').trim()
                    val runs = mutableListOf(
                        DocRun(if (checked) "☑ " else "☐ ", bold = false)
                    )
                    runs.addAll(parseInline(text))
                    blocks.add(DocBlock.Paragraph(runs))
                }

                line.matches(Regex("""^[-*+]\s+.*""")) -> {
                    flushQuote()
                    blocks.add(DocBlock.Paragraph(parseInline(line.drop(1).trim()), bullet = true))
                }

                line.matches(Regex("""^\d+[.)]\s+.*""")) -> {
                    flushQuote()
                    val num = line.takeWhile { it.isDigit() }.toIntOrNull() ?: 1
                    blocks.add(
                        DocBlock.Paragraph(
                            parseInline(line.substringAfter(' ').trim()),
                            ordered = num
                        )
                    )
                }

                else -> {
                    flushQuote()
                    blocks.add(DocBlock.Paragraph(parseInline(line)))
                }
            }
            i++
        }
        flushQuote()
        return blocks
    }

    private val IMAGE_ONLY = Regex("""^!\[[^\]]*]\(.+\)$""")

    /** HTML 块级标记：@@h1@@内容 / @@li@@内容 / @@table0@@ 等 */
    private val MARKER = Regex("""^@@([A-Za-z0-9]+)@@(.*)$""")

    /**
     * 是否为表格行。容忍省略首尾竖线的写法（如 "A | B"），
     * 但排除标题、引用、列表、代码等明显不是表格的行。
     */
    private fun isTableRow(s: String): Boolean {
        if (s.length < 3 || !s.contains('|')) return false
        if (s.startsWith("#") || s.startsWith(">") || s.startsWith("```")) return false
        if (s.matches(Regex("""^[-*+]\s+.*"""))) return false
        if (s.matches(Regex("""^\d+[.)]\s+.*"""))) return false
        return true
    }

    private fun isSeparatorRow(s: String): Boolean {
        if (!s.contains('|')) return false
        val cells = splitCells(s)
        return cells.isNotEmpty() && cells.all { it.matches(Regex(""":?-{2,}:?""")) }
    }

    private fun splitCells(row: String): List<String> =
        row.trim().trim('|').split("|").map { it.trim() }

    /** 列数对齐：表头与各行统一到最大列数，避免渲染/导出时错位 */
    private fun normalizeTable(
        header: List<String>,
        rows: List<List<String>>
    ): DocBlock.Table {
        val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtLeast(1)
        fun pad(r: List<String>) = List(cols) { idx -> r.getOrElse(idx) { "" } }
        return DocBlock.Table(pad(header), rows.map { pad(it) })
    }

    private fun parseImageBlock(line: String): DocBlock {
        val alt = Regex("""^!\[([^\]]*)]""").find(line)?.groupValues?.get(1).orEmpty()
        val url = line.substringAfter("](", "").substringBeforeLast(")")
        return imageFromUrl(url, alt)
    }

    /** 从 data:image base64 或普通 URL 构造图片块（不解码，保持轻量） */
    private fun imageFromUrl(url: String, alt: String): DocBlock.Image =
        DocBlock.Image(url, alt)

    // ==================== HTML ====================

    private fun parseHtml(html: String): List<DocBlock> {
        var body = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
            .replace(Regex("(?is)<!DOCTYPE[^>]*>"), "")
            .replace(Regex("(?is)<head[^>]*>.*?</head>"), "")
        Regex("(?is)<body[^>]*>(.*?)</body>").find(body)?.let { body = it.groupValues[1] }

        // 1. 先把 <table> / <pre> 整段抽出为占位符，避免后续规范化破坏其结构
        val tableStore = mutableListOf<DocBlock>()
        body = Regex("(?is)<table[^>]*>.*?</table>").replace(body) { m ->
            val t = parseHtmlTable(m.value)
            if (t != null) {
                tableStore.add(t)
                "\n@@TABLE${tableStore.size - 1}@@\n"
            } else {
                "\n"
            }
        }

        val codeStore = mutableListOf<DocBlock>()
        body = Regex("(?is)<pre[^>]*>(.*?)</pre>").replace(body) { m ->
            val inner = m.groupValues[1]
            // 尽量取 <code class="language-xxx"> 里的语言
            val lang = Regex("""(?i)class="[^"]*language-([a-zA-Z0-9+#-]+)""")
                .find(inner)?.groupValues?.get(1).orEmpty()
            val content = stripTags(inner).trim('\n')
            codeStore.add(DocBlock.Code(content.lines(), lang))
            "\n@@CODE${codeStore.size - 1}@@\n"
        }

        // 2. 块级标签打上类型标记（保留类型信息，不再丢成纯换行）
        val normalized = body
            .replace(
                Regex("(?i)<(h[1-6]|p|li|div|ul|ol|blockquote)\\b[^>]*>"),
                "\n@@$1@@"
            )
            .replace(
                Regex("(?i)</(h[1-6]|p|li|div|ul|ol|blockquote)>"),
                "\n"
            )
            .replace(Regex("""(?i)<br\s*/?>"""), "\n")
            .replace(Regex("""(?i)<hr\s*/?>"""), "\n@@HR@@\n")

        val blocks = mutableListOf<DocBlock>()
        val textBuf = mutableListOf<String>()

        fun flushText() {
            if (textBuf.isNotEmpty()) {
                val joined = textBuf.joinToString(" ").trim()
                if (joined.isNotEmpty()) {
                    blocks.add(DocBlock.Paragraph(parseInline(joined)))
                }
                textBuf.clear()
            }
        }

        /** 取出 @@类型@@内容 */
        fun splitMarker(line: String): Pair<String, String>? {
            val m = MARKER.find(line) ?: return null
            return m.groupValues[1].lowercase() to m.groupValues[2]
        }

        val lines = normalized.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) {
                flushText()
                i++
                continue
            }

            when {
                // ---- 带类型标记的 HTML 块 ----
                splitMarker(line) != null -> {
                    val (kind, rest) = splitMarker(line)!!
                    flushText()
                    val content = rest.trim()
                    when {
                        kind.startsWith("table") -> {
                            val idx = kind.removePrefix("table").toIntOrNull()
                            tableStore.getOrNull(idx ?: -1)?.let { blocks.add(it) }
                        }

                        kind.startsWith("code") -> {
                            val idx = kind.removePrefix("code").toIntOrNull()
                            codeStore.getOrNull(idx ?: -1)?.let { blocks.add(it) }
                        }

                        kind == "hr" -> blocks.add(DocBlock.Divider)

                        kind.matches(Regex("h[1-6]")) -> {
                            val level = kind[1].digitToIntOrNull() ?: 2
                            if (content.isEmpty()) blocks.add(DocBlock.Divider)
                            else blocks.add(DocBlock.Heading(level, parseInline(content)))
                        }

                        kind == "li" -> if (content.isNotEmpty()) {
                            blocks.add(DocBlock.Paragraph(parseInline(content), bullet = true))
                        }

                        kind == "blockquote" -> if (content.isNotEmpty()) {
                            blocks.add(DocBlock.Quote(parseInline(content)))
                        }

                        else -> if (content.isNotEmpty()) {
                            blocks.add(DocBlock.Paragraph(parseInline(content)))
                        }
                    }
                }

                line.matches(Regex("(?i)^<hr\\s*/?>.*")) -> {
                    flushText(); blocks.add(DocBlock.Divider)
                }

                line.matches(Regex("(?i)^<h[1-6].*")) -> {
                    flushText()
                    val level = Regex("(?i)<h([1-6])").find(line)
                        ?.groupValues?.get(1)?.toIntOrNull() ?: 2
                    val content = collectInlineText(line, lines, i) { it }
                    val consumed = consumedLines(line)
                    blocks.add(DocBlock.Heading(level, parseInline(content)))
                    i += consumed - 1
                }

                line.matches(Regex("(?i)^<pre.*")) -> {
                    flushText()
                    val buf = mutableListOf<String>()
                    var j = i
                    while (j < lines.size) {
                        val cur = lines[j]
                        buf.add(cur.replace(Regex("(?i)</?pre[^>]*>"), ""))
                        if (cur.contains("</pre>", ignoreCase = true)) break
                        j++
                    }
                    blocks.add(DocBlock.Code(buf.map { it }.drop(1).dropLast(0)))
                    i = j
                }

                line.matches(Regex("(?i)^<table.*")) -> {
                    flushText()
                    // 收集整张表
                    val raw = StringBuilder()
                    var j = i
                    while (j < lines.size) {
                        raw.append(lines[j]).append('\n')
                        if (lines[j].contains("</table>", ignoreCase = true)) break
                        j++
                    }
                    parseHtmlTable(raw.toString())?.let { blocks.add(it) }
                    i = j
                }

                line.matches(Regex("(?i)^<li.*")) -> {
                    flushText()
                    val content = line.replace(Regex("(?i)</?li[^>]*>"), "").trim()
                    if (content.isNotEmpty()) {
                        blocks.add(DocBlock.Paragraph(parseInline(content), bullet = true))
                    }
                }

                line.matches(Regex("(?i)</?(ul|ol|div|tr|tbody|thead|table|blockquote)[^>]*>")) -> {
                    flushText()
                }

                else -> textBuf.add(line)
            }
            i++
        }
        flushText()
        return blocks
    }

    private fun consumedLines(line: String): Int = if (line.contains("</h")) 1 else 1

    private fun collectInlineText(
        line: String,
        lines: List<String>,
        index: Int,
        transform: (String) -> String
    ): String = transform(line.replace(Regex("(?i)</?h[1-6][^>]*>"), "")).trim()

    private fun parseHtmlTable(html: String): DocBlock.Table? {
        val rowMatches = Regex("(?is)<tr[^>]*>(.*?)</tr>").findAll(html).toList()
        if (rowMatches.isEmpty()) return null
        val rows = rowMatches.map { row ->
            Regex("(?is)<t[hd][^>]*>(.*?)</t[hd]>").findAll(row.groupValues[1])
                .map { stripTags(it.groupValues[1]) }
                .toList()
        }
        val header = rows.firstOrNull().orEmpty()
        return DocBlock.Table(header, rows.drop(1))
    }

    private fun stripTags(s: String): String =
        s.replace(Regex("(?s)<[^>]+>"), "").replace("&nbsp;", " ").trim()

    // ==================== 行内解析 ====================

    /**
     * 解析行内 Markdown 与 HTML 标签，产出带样式的片段。
     * 支持：**粗体** __粗体__ *斜体* _斜体_ ~~删除线~~ `代码`
     *      <b> <strong> <i> <em> <del> <s> <code> <br> [文字](链接) ![alt](url)
     */
    fun parseInline(input: String): List<DocRun> {
        val runs = mutableListOf<DocRun>()
        val sb = StringBuilder()
        var bold = false
        var italic = false
        var strike = false
        var code = false
        var pendingBreak = false
        var i = 0

        fun flush() {
            if (sb.isNotEmpty()) {
                runs.add(DocRun(sb.toString(), bold, italic, strike, code, pendingBreak))
                sb.clear()
                pendingBreak = false
            }
        }

        while (i < input.length) {
            val c = input[i]
            when {
                // ---- 行内代码：内容不做其它解析 ----
                c == '`' -> {
                    val end = input.indexOf('`', i + 1)
                    if (end > i) {
                        val content = input.substring(i + 1, end)
                        if (content.isNotEmpty()) {
                            runs.add(DocRun(content, code = true))
                        }
                        i = end + 1
                    } else {
                        sb.append(c); i++
                    }
                }

                // ---- HTML 标签 ----
                c == '<' -> {
                    val end = input.indexOf('>', i)
                    if (end < 0) {
                        sb.append(c); i++
                    } else {
                        val tag = input.substring(i + 1, end).trim()
                        val closing = tag.startsWith("/")
                        val name = tag.removePrefix("/").trimEnd('/')
                            .substringBefore(' ').lowercase()
                        when (name) {
                            "b", "strong" -> { flush(); bold = !closing }
                            "i", "em" -> { flush(); italic = !closing }
                            "del", "s", "strike" -> { flush(); strike = !closing }
                            "code", "kbd", "tt" -> { flush(); code = !closing }
                            "br" -> { flush(); pendingBreak = true }
                            else -> flush() // 其它标签（p/div/span…）仅忽略
                        }
                        i = end + 1
                    }
                }

                // ---- 链接：保留文字，丢弃地址 ----
                c == '[' -> {
                    val close = input.indexOf(']', i)
                    val open = if (close >= 0) input.indexOf('(', close) else -1
                    if (close > i && open == close + 1) {
                        val text = input.substring(i + 1, close)
                        sb.append(text)
                        val endParen = input.indexOf(')', open)
                        i = if (endParen > 0) endParen + 1 else close + 1
                    } else {
                        sb.append(c); i++
                    }
                }

                // ---- 加粗 / 斜体 ----
                c == '*' || c == '_' -> {
                    val doubled = i + 1 < input.length && input[i + 1] == c
                    if (doubled) {
                        flush(); bold = !bold; i += 2
                    } else {
                        flush(); italic = !italic; i += 1
                    }
                }

                // ---- 删除线 ----
                c == '~' && i + 1 < input.length && input[i + 1] == '~' -> {
                    flush(); strike = !strike; i += 2
                }

                // ---- 转义 ----
                c == '\\' && i + 1 < input.length -> {
                    sb.append(input[i + 1]); i += 2
                }

                else -> {
                    sb.append(c); i++
                }
            }
        }
        flush()
        if (runs.isEmpty()) runs.add(DocRun(""))
        return runs
    }

    /** 取纯文本（用于 Word 表格单元格等） */
    fun runsToPlainText(runs: List<DocRun>): String = runs.joinToString("") { it.text }
}

/** 交互式图片尺寸解析（纯 Kotlin，支持 PNG / JPEG），供 Word 计算显示大小 */
object ImageInfo {

    fun size(bytes: ByteArray): Pair<Int, Int>? {
        pngSize(bytes)?.let { return it }
        jpegSize(bytes)?.let { return it }
        return null
    }

    private fun pngSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 24) return null
        if (b[0] != 0x89.toByte() || b[1] != 'P'.code.toByte() ||
            b[2] != 'N'.code.toByte() || b[3] != 'G'.code.toByte()
        ) return null
        val w = readIntBE(b, 16)
        val h = readIntBE(b, 20)
        return if (w > 0 && h > 0) w to h else null
    }

    private fun jpegSize(b: ByteArray): Pair<Int, Int>? {
        if (b.size < 4) return null
        if (b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val marker = b[i + 1].toInt() and 0xFF
            if (marker == 0xD8 || marker == 0x01 || (marker in 0xD0..0xD7)) { i += 2; continue }
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (len < 2) return null
            val isSof = marker in 0xC0..0xCF &&
                marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isSof) {
                val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                return if (w > 0 && h > 0) w to h else null
            }
            i += 2 + len
        }
        return null
    }

    private fun readIntBE(b: ByteArray, offset: Int): Int =
        ((b[offset].toInt() and 0xFF) shl 24) or
            ((b[offset + 1].toInt() and 0xFF) shl 16) or
            ((b[offset + 2].toInt() and 0xFF) shl 8) or
            (b[offset + 3].toInt() and 0xFF)
}