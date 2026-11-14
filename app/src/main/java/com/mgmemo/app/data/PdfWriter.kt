package com.mgmemo.app.data

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.util.Log
import java.io.OutputStream

/**
 * 原生 Canvas PDF 渲染器：
 * 直接把文档块绘制到 PdfDocument 上（不使用 WebView，因此不存在“空白页”问题）。
 * 支持标题分级字号、加粗/斜体、列表、引用、代码块底色、表格线框、图片与分页。
 */
object PdfWriter {

    private const val PAGE_W = 595 // A4 宽（point）
    private const val PAGE_H = 842 // A4 高
    private const val MARGIN = 48f
    private const val CONTENT_W = PAGE_W - MARGIN * 2

    // 字号（point）
    private const val SZ_BODY = 11f
    private const val SZ_CODE = 9.5f
    private const val SZ_H1 = 22f
    private const val SZ_H2 = 18f
    private const val SZ_H3 = 16f
    private const val SZ_H4 = 14f
    private const val SZ_H5 = 12.5f

    private val COLOR_TEXT = Color.rgb(0x24, 0x29, 0x2f)
    private val COLOR_MUTED = Color.rgb(0x57, 0x60, 0x6a)
    private val COLOR_BORDER = Color.rgb(0xd0, 0xd7, 0xde)
    private val COLOR_CODE_BG = Color.rgb(0xf2, 0xf4, 0xf7)
    private val COLOR_TABLE_HEAD = Color.rgb(0xf0, 0xf3, 0xf6)
    private val COLOR_QUOTE_BAR = Color.rgb(0xc2, 0xc9, 0xbc)

    fun write(blocks: List<DocBlock>, out: OutputStream, docTitle: String = "") {
        val pdf = PdfDocument()
        val renderer = Renderer(pdf)
        try {
            renderer.start()

            if (docTitle.isNotBlank()) {
                renderer.heading(1, listOf(DocRun(docTitle, bold = true)))
            }
            if (blocks.isEmpty()) {
                renderer.paragraph(listOf(DocRun("（空笔记）")), SZ_BODY, muted = true)
            }
            blocks.forEach { block ->
                try {
                    renderer.block(block)
                } catch (e: Exception) {
                    // 单个块出错不影响整篇导出
                    Log.e("MGMemo", "PDF 块渲染失败: $block", e)
                }
            }
            renderer.finish()
            pdf.writeTo(out)
        } finally {
            try {
                pdf.close()
            } catch (_: Exception) {
            }
        }
    }

    // ==================== 渲染器 ====================

    private class Style(
        val size: Float,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val strike: Boolean = false,
        val muted: Boolean = false
    )

    private class Seg(val text: String, val paint: Paint, val strike: Boolean)

    private val paintCache = HashMap<Int, Paint>()

    private fun paintFor(style: Style): Paint {
        val key = style.hashCode()
        paintCache[key]?.let { return it }
        val styleFlag = when {
            style.bold && style.italic -> Typeface.BOLD_ITALIC
            style.bold -> Typeface.BOLD
            style.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val family = if (style.code) "monospace" else "sans-serif"
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(family, styleFlag)
            textSize = style.size
            color = if (style.muted) COLOR_MUTED else COLOR_TEXT
            isSubpixelText = true
        }
        paintCache[key] = paint
        return paint
    }

    private class Renderer(private val pdf: PdfDocument) {

        private var pageNo = 1
        private var page: PdfDocument.Page = pdf.startPage(pageInfo(1))
        private var canvas: Canvas = page.canvas
        private var y = MARGIN

        fun start() {
            canvas.drawColor(Color.WHITE)
        }

        private fun pageInfo(no: Int) =
            PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, no).create()

        private val bottomLimit = PAGE_H - MARGIN

        /** 结束当前页（写页脚），并在需要时翻开新页 */
        private fun newPage() {
            drawFooter()
            pdf.finishPage(page)
            pageNo++
            page = pdf.startPage(pageInfo(pageNo))
            canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            y = MARGIN
        }

        private fun drawFooter() {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                textSize = 8f
                color = COLOR_MUTED
            }
            canvas.drawText("第 $pageNo 页", PAGE_W - MARGIN - 34f, PAGE_H - 24f, paint)
        }

        fun finish() {
            drawFooter()
            pdf.finishPage(page)
        }

        /** 确保当前页剩余空间足够，不够则换页 */
        private fun ensure(height: Float) {
            if (y + height > bottomLimit) newPage()
        }

        fun block(block: DocBlock) {
            when (block) {
                is DocBlock.Heading -> heading(block.level, block.runs)

                is DocBlock.Paragraph -> paragraph(
                    block.runs,
                    SZ_BODY,
                    bullet = block.bullet,
                    ordered = block.ordered
                )

                is DocBlock.Quote -> quote(block.runs)

                is DocBlock.Code -> code(block.lines)

                is DocBlock.Table -> table(block)

                is DocBlock.Image -> image(block)

                DocBlock.Divider -> {
                    ensure(18f)
                    y += 8f
                    val p = Paint().apply {
                        color = COLOR_BORDER
                        strokeWidth = 1f
                    }
                    canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, p)
                    y += 12f
                }
            }
        }

        // ---------- 标题 ----------

        fun heading(level: Int, runs: List<DocRun>) {
            val size = when (level) {
                1 -> SZ_H1
                2 -> SZ_H2
                3 -> SZ_H3
                4 -> SZ_H4
                else -> SZ_H5
            }
            y += size * 0.7f
            val style = Style(size, bold = true)
            val lines = wrap(runs.map { it.copy(bold = true) }, style, CONTENT_W)
            val leading = size * 1.45f
            lines.forEach { line ->
                ensure(leading)
                drawLine(line, MARGIN, y, leading)
                y += leading
            }
            if (level <= 2) {
                ensure(6f)
                y += 3f
                val p = Paint().apply {
                    color = COLOR_BORDER
                    strokeWidth = if (level == 1) 1.6f else 1f
                }
                canvas.drawLine(MARGIN, y, PAGE_W - MARGIN, y, p)
                y += 3f
            }
            y += size * 0.35f
        }

        // ---------- 段落 ----------

        fun paragraph(
            runs: List<DocRun>,
            size: Float,
            bullet: Boolean = false,
            ordered: Int? = null,
            muted: Boolean = false,
            indentLeft: Float = 0f,
            maxWidth: Float = CONTENT_W
        ) {
            val prefix = when {
                bullet -> "• "
                ordered != null -> "$ordered. "
                else -> ""
            }
            val left = MARGIN + indentLeft
            val bodyLeft = if (prefix.isNotEmpty()) left + 16f else left
            val width = maxWidth - (bodyLeft - left)

            val style = Style(size, muted = muted)
            val lines = wrap(runs, style, width)
            val leading = size * 1.6f
            val hanging = bodyLeft - left

            lines.forEachIndexed { index, line ->
                ensure(leading)
                if (index == 0 && prefix.isNotEmpty()) {
                    canvas.drawText(prefix, left, y + leading * 0.76f, paintFor(style))
                }
                drawLine(line, if (index == 0) bodyLeft else left + hanging, y, leading)
                y += leading
            }
        }

        // ---------- 引用 ----------

        private fun quote(runs: List<DocRun>) {
            val style = Style(SZ_BODY, muted = true)
            val lines = wrap(runs, style, CONTENT_W - 24f)
            val leading = SZ_BODY * 1.6f
            val startY = y
            lines.forEach { line ->
                ensure(leading)
                drawLine(line, MARGIN + 18f, y, leading)
                y += leading
            }
            val p = Paint().apply { color = COLOR_QUOTE_BAR }
            canvas.drawRect(MARGIN + 4f, startY, MARGIN + 9f, y - leading * 0.2f, p)
            y += 6f
        }

        // ---------- 代码块 ----------

        private fun code(lines: List<String>) {
            val style = Style(SZ_CODE, code = true)
            val paint = paintFor(style)
            val leading = SZ_CODE * 1.55f
            val bg = Paint().apply { color = COLOR_CODE_BG }
            y += 4f

            if (lines.isEmpty()) {
                ensure(leading)
                canvas.drawRect(MARGIN, y, PAGE_W - MARGIN, y + leading, bg)
                y += leading
            }

            lines.forEach { raw ->
                val wrapped = wrapPlain(raw.ifEmpty { " " }, paint, CONTENT_W - 20f)
                wrapped.forEach { text ->
                    ensure(leading)
                    // 分页时让底色连续
                    val top = if (y < MARGIN) MARGIN else y
                    canvas.drawRect(MARGIN, top, PAGE_W - MARGIN, top + leading, bg)
                    canvas.drawText(text, MARGIN + 10f, top + leading * 0.74f, paint)
                    y += leading
                }
            }
            y += 6f
        }

        // ---------- 表格 ----------

        private fun table(t: DocBlock.Table) {
            val cols = maxOf(
                t.header.size,
                t.rows.maxOfOrNull { it.size } ?: 0
            ).coerceAtLeast(1)

            val textPaint = paintFor(Style(SZ_BODY - 0.5f))
            val headPaint = paintFor(Style(SZ_BODY - 0.5f, bold = true))

            // 按内容比例分配列宽（最小 55pt）
            val natural = FloatArray(cols) { 55f }
            for (c in 0 until cols) {
                t.header.getOrNull(c)?.let {
                    natural[c] = maxOf(natural[c], textPaint.measureText(it) + 16f)
                }
                t.rows.forEach { row ->
                    row.getOrNull(c)?.let {
                        natural[c] = maxOf(natural[c], textPaint.measureText(it) + 16f)
                    }
                }
            }
            val total = natural.sum()
            val widths = FloatArray(cols) { natural[it] / total * CONTENT_W }

            fun rowHeight(cells: List<String>): Float {
                val leading = (SZ_BODY - 0.5f) * 1.5f
                var maxLines = 1
                for (c in 0 until cols) {
                    val cell = cells.getOrNull(c).orEmpty()
                    val inner = (widths[c] - 12f).coerceAtLeast(20f)
                    maxLines = maxOf(maxLines, wrapPlain(cell, textPaint, inner).size)
                }
                return maxLines * leading + 8f
            }

            val leading = (SZ_BODY - 0.5f) * 1.5f

            fun drawRow(cells: List<String>, header: Boolean) {
                val h = rowHeight(cells)
                ensure(h)
                // 表头底色
                if (header) {
                    val bg = Paint().apply { color = COLOR_TABLE_HEAD }
                    canvas.drawRect(MARGIN, y, MARGIN + CONTENT_W, y + h, bg)
                }
                // 边框
                val border = Paint().apply {
                    color = COLOR_BORDER
                    style = Paint.Style.STROKE
                    strokeWidth = 0.8f
                }
                canvas.drawRect(MARGIN, y, MARGIN + CONTENT_W, y + h, border)

                var x = MARGIN
                for (c in 0 until cols) {
                    if (c > 0) {
                        canvas.drawLine(x, y, x, y + h, border)
                    }
                    val cell = cells.getOrNull(c).orEmpty()
                    val paint = if (header) headPaint else textPaint
                    val inner = (widths[c] - 12f).coerceAtLeast(20f)
                    val lines = wrapPlain(cell, paint, inner)
                    var cy = y
                    lines.forEach { line ->
                        canvas.drawText(line, x + 6f, cy + leading * 0.78f, paint)
                        cy += leading
                    }
                    x += widths[c]
                }
                y += h
            }

            if (t.header.isNotEmpty()) drawRow(t.header, header = true)
            t.rows.forEach { drawRow(it, header = false) }
            y += 8f
        }

        // ---------- 图片 ----------

        private fun image(block: DocBlock.Image) {
            val raw = block.bytes()
            if (raw == null || raw.isEmpty()) {
                paragraph(
                    listOf(DocRun("[图片] ${block.alt}")),
                    SZ_BODY,
                    muted = true
                )
                return
            }
            val bitmap = try {
                BitmapFactory.decodeByteArray(raw, 0, raw.size)
            } catch (e: Exception) {
                null
            }
            if (bitmap == null) {
                paragraph(listOf(DocRun("[图片] ${block.alt}")), SZ_BODY, muted = true)
                return
            }
            try {
                val maxW = CONTENT_W
                val maxH = PAGE_H - MARGIN * 2 - 20f
                var w = bitmap.width.toFloat()
                var h = bitmap.height.toFloat()
                val scale = minOf(maxW / w, maxH / h, 1f)
                w *= scale
                h *= scale
                ensure(h + 8f)
                val dst = RectF(MARGIN + (CONTENT_W - w) / 2f, y, MARGIN + (CONTENT_W + w) / 2f, y + h)
                canvas.drawBitmap(bitmap, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
                y += h + 10f
            } finally {
                bitmap.recycle()
            }
        }

        // ---------- 文本排版 ----------

        private fun styleOf(run: DocRun, base: Style): Style = Style(
            size = base.size,
            bold = run.bold || base.bold,
            italic = run.italic || base.italic,
            code = run.code,
            strike = run.strike,
            muted = base.muted
        )

        private fun toSegments(runs: List<DocRun>, base: Style, width: Float): List<Seg> {
            val segs = mutableListOf<Seg>()
            runs.forEach { run ->
                if (run.lineBreakBefore) {
                    segs.add(Seg("\n", paintFor(base), false))
                }
                val style = styleOf(run, base)
                val paint = paintFor(style)
                val text = run.text
                if (text.isEmpty()) return@forEach
                tokenize(text).forEach { tk ->
                    segs.add(Seg(tk, paint, style.strike))
                }
            }
            return segs
        }

        /** 拆词：换行符、空格、CJK 单字、拉丁词 */
        private fun tokenize(text: String): List<String> {
            val tokens = mutableListOf<String>()
            val sb = StringBuilder()
            fun flush() {
                if (sb.isNotEmpty()) {
                    tokens.add(sb.toString())
                    sb.clear()
                }
            }
            text.forEach { ch ->
                when {
                    ch == '\n' -> { flush(); tokens.add("\n") }
                    ch == '\t' -> { flush(); tokens.add("    ") }
                    ch == ' ' -> { flush(); tokens.add(" ") }
                    isCjk(ch) -> { flush(); tokens.add(ch.toString()) }
                    else -> sb.append(ch)
                }
            }
            flush()
            return tokens
        }

        private fun isCjk(ch: Char): Boolean {
            val c = ch.code
            return c in 0x2E80..0x9FFF || c in 0xF900..0xFAFF ||
                c in 0xFF00..0xFFEF || c in 0x3000..0x303F
        }

        /** 把片段按宽度折行 */
        private fun wrap(runs: List<DocRun>, base: Style, width: Float): List<List<Seg>> =
            wrapSegments(toSegments(runs, base, width), width)

        private fun wrapPlain(text: String, paint: Paint, width: Float): List<String> {
            val segs = tokenize(text).map { Seg(it, paint, false) }
            return wrapSegments(segs, width).map { line ->
                line.joinToString("") { it.text }
            }
        }

        private fun wrapSegments(segments: List<Seg>, width: Float): List<List<Seg>> {
            val lines = mutableListOf<List<Seg>>()
            var current = mutableListOf<Seg>()
            var currentW = 0f

            fun pushLine() {
                lines.add(current)
                current = mutableListOf()
                currentW = 0f
            }

            segments.forEach { seg ->
                if (seg.text == "\n") {
                    pushLine()
                    return@forEach
                }
                val w = seg.paint.measureText(seg.text)
                if (currentW + w > width && currentW > 0f) pushLine()
                if (seg.text == " " && current.isEmpty()) return@forEach // 行首空格忽略
                current.add(seg)
                currentW += w
            }
            if (current.isNotEmpty() || lines.isEmpty()) lines.add(current)
            return lines
        }

        private fun drawLine(line: List<Seg>, x0: Float, top: Float, leading: Float) {
            var x = x0
            val baseline = top + leading * 0.76f
            var merged: Seg? = null
            var mergedW = 0f
            line.forEach { seg ->
                if (merged != null && merged.paint === seg.paint && merged.strike == seg.strike) {
                    merged = Seg(merged.text + seg.text, seg.paint, seg.strike)
                    mergedW += seg.paint.measureText(seg.text)
                } else {
                    merged?.let { m ->
                        canvas.drawText(m.text, x, baseline, m.paint)
                        if (m.strike) {
                            val p = Paint().apply {
                                color = m.paint.color
                                strokeWidth = 0.9f
                            }
                            canvas.drawLine(
                                x, baseline - m.paint.textSize * 0.3f,
                                x + mergedW, baseline - m.paint.textSize * 0.3f, p
                            )
                        }
                        x += mergedW
                    }
                    merged = seg
                    mergedW = seg.paint.measureText(seg.text)
                }
            }
            merged?.let { m ->
                canvas.drawText(m.text, x, baseline, m.paint)
                if (m.strike) {
                    val p = Paint().apply {
                        color = m.paint.color
                        strokeWidth = 0.9f
                    }
                    canvas.drawLine(
                        x, baseline - m.paint.textSize * 0.3f,
                        x + mergedW, baseline - m.paint.textSize * 0.3f, p
                    )
                }
            }
        }
    }
}