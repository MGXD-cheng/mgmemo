package com.mgmemo.app.data

/**
 * HTML → Markdown（所见即所得编辑回写用）。
 * 原先依赖 CDN 上的 Turndown，网络不好时会静默失效，这里改为纯 Kotlin 实现。
 */
object MarkdownWriter {

    /** 把 contenteditable 回传的 innerHTML 转回 Markdown */
    fun fromHtml(html: String): String {
        if (html.isBlank()) return ""
        val blocks = try {
            DocParser.parseHtmlFragment(html)
        } catch (e: Exception) {
            return ""
        }
        return blocksToMarkdown(blocks)
    }

    fun blocksToMarkdown(blocks: List<DocBlock>): String {
        if (blocks.isEmpty()) return ""
        val sb = StringBuilder()
        blocks.forEach { b ->
            when (b) {
                is DocBlock.Heading -> {
                    sb.append("#".repeat(b.level.coerceIn(1, 6)))
                        .append(' ')
                        .append(runs(b.runs))
                }

                is DocBlock.Paragraph -> {
                    val prefix = when {
                        b.bullet -> "- "
                        b.ordered != null -> "${b.ordered}. "
                        else -> ""
                    }
                    sb.append(prefix).append(runs(b.runs))
                }

                is DocBlock.Quote -> sb.append("> ").append(runs(b.runs))

                is DocBlock.Code -> {
                    sb.append("```").append(b.lang).append('\n')
                    sb.append(b.lines.joinToString("\n"))
                    sb.append("\n```")
                }

                is DocBlock.Table -> {
                    val header = b.header.joinToString(" | ", prefix = "| ", postfix = " |")
                    val sep = "| " + b.header.joinToString(" | ") { "---" } + " |"
                    sb.append(header).append('\n').append(sep)
                    b.rows.forEach { row ->
                        sb.append('\n').append(
                            row.joinToString(" | ", prefix = "| ", postfix = " |")
                        )
                    }
                }

                is DocBlock.Image -> {
                    if (b.url.isNotBlank()) sb.append("![").append(b.alt).append("](")
                        .append(b.url).append(")")
                }

                DocBlock.Divider -> sb.append("---")
            }
            sb.append("\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun runs(list: List<DocRun>): String {
        val sb = StringBuilder()
        list.forEach { r ->
            if (r.lineBreakBefore) sb.append("  \n")
            if (r.text.isEmpty()) return@forEach
            var s = r.text
            if (r.code) s = "`$s`"
            if (r.strike) s = "~~$s~~"
            if (r.italic) s = "*$s*"
            if (r.bold) s = "**$s**"
            sb.append(s)
        }
        return sb.toString()
    }
}