package com.mgmemo.app.data

import com.google.gson.GsonBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份文件结构（JSON）。
 * 只包含笔记本体、历史版本与「非敏感」设置——**不含 API Key**，可安全分享/云同步。
 */
data class BackupFile(
    val app: String = "MGMemo",
    val schema: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val noteCount: Int = 0,
    val notes: List<BackupNote> = emptyList(),
    val settings: BackupSettings? = null
)

data class BackupNote(
    val title: String = "",
    val content: String = "",
    val tags: String = "",
    val summary: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val history: List<BackupHistory> = emptyList()
)

data class BackupHistory(
    val content: String = "",
    val timestamp: Long = 0L
)

/** 非敏感设置 */
data class BackupSettings(
    val themeMode: String = "system",
    val editorLayout: String = "preview",
    val customTags: String = ""
)

object BackupManager {

    private val gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    fun toJson(file: BackupFile): String = gson.toJson(file)

    /** 解析备份文件；失败时返回可读的错误原因 */
    fun parse(json: String): Result<BackupFile> {
        if (json.isBlank()) {
            return Result.failure(IllegalArgumentException("文件内容为空"))
        }
        return try {
            val data = gson.fromJson(json, BackupFile::class.java)
                ?: return Result.failure(IllegalArgumentException("无法解析备份文件"))
            if (data.notes.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException("备份文件里没有笔记，可能选错了文件")
                )
            }
            Result.success(data)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("文件格式不正确：${e.message}"))
        }
    }

    /** 备份文件名：MGMemo备份_20261022_2110.json */
    fun fileName(now: Long = System.currentTimeMillis()): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date(now))
        return "MGMemo备份_$ts.json"
    }

    /** 生成备份内容 */
    fun build(
        notes: List<Note>,
        historyOf: (Long) -> List<NoteHistory>,
        settings: AppSettings
    ): BackupFile {
        val backupNotes = notes.map { n ->
            BackupNote(
                title = n.title,
                content = n.content,
                tags = n.tags,
                summary = n.summary,
                createdAt = n.createdAt,
                updatedAt = n.updatedAt,
                history = historyOf(n.id).map { BackupHistory(it.content, it.timestamp) }
            )
        }
        return BackupFile(
            noteCount = backupNotes.size,
            notes = backupNotes,
            settings = BackupSettings(
                themeMode = settings.themeMode,
                editorLayout = settings.editorLayout,
                customTags = settings.customTags
            )
        )
    }
}