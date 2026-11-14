package com.mgmemo.app.data

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** 导出结果：保存到「下载」的 Uri 与是否真正落在下载目录 */
data class SavedFile(val uri: Uri?, val inDownloads: Boolean)

/**
 * 导出工具：纯文本剥离 + 写入系统「下载」目录。
 * PDF / Word 的实际渲染分别由 [PdfWriter] / [WordWriter] 负责，
 * 二者共用 [DocParser] 解析出的统一文档模型。
 */
object ExportUtils {

    // ==================== Markdown → 纯文本 ====================

    /**
     * 去掉全部 Markdown / HTML 标记，输出可直接阅读的纯文本（不走 AI）。
     * 复用统一文档模型，因此与 PDF / Word 导出的内容完全一致。
     */
    fun markdownToPlainText(source: String): String {
        val blocks = DocParser.parse(source)
        if (blocks.isEmpty()) return ""
        val sb = StringBuilder()
        blocks.forEach { block ->
            when (block) {
                is DocBlock.Heading -> {
                    sb.append(DocParser.runsToPlainText(block.runs))
                }

                is DocBlock.Paragraph -> {
                    val prefix = when {
                        block.bullet -> "• "
                        block.ordered != null -> "${block.ordered}. "
                        else -> ""
                    }
                    sb.append(prefix).append(DocParser.runsToPlainText(block.runs))
                }

                is DocBlock.Quote -> {
                    sb.append("＞ ").append(DocParser.runsToPlainText(block.runs))
                }

                is DocBlock.Code -> {
                    sb.append(block.lines.joinToString("\n"))
                }

                is DocBlock.Table -> {
                    (listOf(block.header) + block.rows).forEach { row ->
                        sb.append(row.joinToString("  ")).append("\n")
                    }
                }

                is DocBlock.Image -> {
                    if (block.alt.isNotBlank()) sb.append("[图片] ").append(block.alt)
                }

                DocBlock.Divider -> sb.append("────────")
            }
            sb.append("\n\n")
        }
        // 折叠多余空行
        return sb.toString().replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    // ==================== 导出到系统「下载」 ====================

    /**
     * 把内容写入系统下载目录（Download/MGMemo/）。
     * - Android 10+ 走 MediaStore（无需存储权限）
     * - Android 9 及以下写公共目录，失败则退回应用缓存目录
     * @return 保存结果（Uri + 是否真的落在下载目录）
     */
    fun saveToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        write: (OutputStream) -> Unit
    ): SavedFile {
        // ---- Android 10+：MediaStore Downloads ----
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        android.os.Environment.DIRECTORY_DOWNLOADS + "/MGMemo"
                    )
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    var written = 0L
                    val raw = resolver.openOutputStream(uri)
                    if (raw != null) {
                        // 统计实际写入字节数：为 0 说明写入失败，不能让用户拿到空文件
                        val counting = object : java.io.FilterOutputStream(raw) {
                            override fun write(b: Int) {
                                raw.write(b)
                                written++
                            }

                            override fun write(b: ByteArray, off: Int, len: Int) {
                                raw.write(b, off, len)
                                written += len
                            }
                        }
                        try {
                            write(counting)
                            counting.flush()
                        } finally {
                            try {
                                counting.close()
                            } catch (_: Exception) {
                            }
                        }
                    }
                    if (written > 0L) {
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                        return SavedFile(uri, true)
                    }
                    // 写入为空 → 删除残留空文件，走下面的兜底路径
                    Log.e("MGMemo", "下载目录写入字节数为 0，回退到缓存目录")
                    try {
                        resolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                }
            } catch (e: Exception) {
                Log.e("MGMemo", "写入下载目录失败（MediaStore）", e)
            }
        }

        // ---- Android 9 及以下：公共下载目录 ----
        try {
            @Suppress("DEPRECATION")
            val dir = File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                ),
                "MGMemo"
            )
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            FileOutputStream(file).use { write(it) }
            val uri = FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            return SavedFile(uri, true)
        } catch (e: Exception) {
            Log.e("MGMemo", "写入下载目录失败（File）", e)
        }

        // ---- 兜底：应用缓存目录（仍可分享） ----
        return try {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, fileName)
            FileOutputStream(file).use { write(it) }
            SavedFile(
                FileProvider.getUriForFile(
                    context, "${context.packageName}.fileprovider", file
                ),
                false
            )
        } catch (e: Exception) {
            Log.e("MGMemo", "写入缓存目录失败", e)
            SavedFile(null, false)
        }
    }
}