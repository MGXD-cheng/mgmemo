package com.mgmemo.app

import com.mgmemo.app.data.AppSettings
import com.mgmemo.app.data.BackupManager
import com.mgmemo.app.data.Note
import com.mgmemo.app.data.NoteHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份序列化 / 反序列化的纯 JVM 测试 */
class BackupTest {

    private fun sampleBackup(): String {
        val notes = listOf(
            Note(
                id = 1,
                title = "练声计划",
                content = "# 内容\n\n正文",
                tags = "音乐,练习",
                summary = "每天 15 分钟",
                createdAt = 1000L,
                updatedAt = 2000L
            )
        )
        val history = listOf(
            NoteHistory(noteId = 1, content = "旧版本内容", timestamp = 500L)
        )
        val file = BackupManager.build(
            notes = notes,
            historyOf = { history },
            settings = AppSettings(
                themeMode = "dark",
                editorLayout = "split",
                customTags = "旅行"
            )
        )
        return BackupManager.toJson(file)
    }

    @Test
    fun `备份包含笔记与历史且可还原`() {
        val json = sampleBackup()
        assertTrue("应包含笔记数量", json.contains("noteCount"))
        assertFalse("不应包含 API Key 字段", json.contains("aiApiKey"))

        val parsed = BackupManager.parse(json).getOrThrow()
        assertEquals(1, parsed.notes.size)
        assertEquals("练声计划", parsed.notes[0].title)
        assertEquals("音乐,练习", parsed.notes[0].tags)
        assertEquals("每天 15 分钟", parsed.notes[0].summary)
        assertEquals(2000L, parsed.notes[0].updatedAt)
        assertEquals(1, parsed.notes[0].history.size)
        assertEquals("旧版本内容", parsed.notes[0].history[0].content)
        assertEquals("dark", parsed.settings?.themeMode)
        assertEquals("旅行", parsed.settings?.customTags)
    }

    @Test
    fun `内容里的特殊字符可安全往返`() {
        val tricky = "引号\"反斜杠\\换行\n尖括号<tag>&符号"
        val file = BackupManager.build(
            notes = listOf(Note(title = tricky, content = tricky)),
            historyOf = { emptyList() },
            settings = AppSettings()
        )
        val parsed = BackupManager.parse(BackupManager.toJson(file)).getOrThrow()
        assertEquals(tricky, parsed.notes[0].content)
        assertEquals(tricky, parsed.notes[0].title)
    }

    @Test
    fun `空内容与非法内容解析失败`() {
        assertTrue("空字符串应失败", BackupManager.parse("").isFailure)
        assertTrue("非 JSON 应失败", BackupManager.parse("这不是备份").isFailure)
        assertTrue("没有笔记应失败", BackupManager.parse("{\"notes\":[]}").isFailure)
    }

    @Test
    fun `备份文件名带时间戳且以 json 结尾`() {
        val name = BackupManager.fileName(0L)
        assertTrue(name.startsWith("MGMemo备份_"))
        assertTrue(name.endsWith(".json"))
    }
}