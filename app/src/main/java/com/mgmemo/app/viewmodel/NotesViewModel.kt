package com.mgmemo.app.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.print.PrintAttributes
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mgmemo.app.data.AiApi
import com.mgmemo.app.data.AppDatabase
import com.mgmemo.app.data.AppSettings
import com.mgmemo.app.data.ChatMessage
import com.mgmemo.app.data.ChatRequest
import com.mgmemo.app.data.Note
import com.mgmemo.app.data.NoteHistory
import com.mgmemo.app.data.SettingsRepository
import com.mgmemo.app.widget.NotesWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

val SAMPLE_NOTE = """
# 🎉 欢迎使用 MGMemo！

这是一款**全能型原生 Android 备忘录**，支持 GFM Markdown、表格、任务列表、代码高亮、LaTeX 公式和 Mermaid 图表实时预览。

## 📋 基本语法

**粗体**、*斜体*、~~删除线~~、`行内代码`、[链接](https://developer.android.com)

> 引用：生活不止眼前的 Bug，还有诗和远方的 Feature。

## 📊 表格

| 功能 | 状态 | 说明 |
| ---- | ---- | ---- |
| Markdown 预览 | ✅ | marked 引擎 |
| 代码高亮 | ✅ | highlight.js |
| LaTeX 公式 | ✅ | KaTeX |
| Mermaid 图表 | ✅ | 流程图 / 时序图 |

## ✅ 任务列表

- [x] 自动保存（500ms 防抖）
- [x] 标签系统与全文搜索
- [x] 撤销 / 重做（30 步）
- [ ] 语音输入（试试工具栏的 🎤）
- [ ] AI 智能摘要（记得在设置里配 Key）

## 💻 代码块

```kotlin
fun greet(name: String): String {
    return "Hello, ${'$'}name! 来自 MGMemo 的问候 👋"
}

// 试试左侧编辑，右侧实时预览
val app = greet("Android")
```

## 🧮 LaTeX 数学公式

行内公式：${'$'}E = mc^2${'$'}，爱因斯坦的质能方程。

块级公式：

$$
\int_0^\infty e^{-x^2} dx = \frac{\sqrt{\pi}}{2}
$$

## 📈 Mermaid 流程图

```mermaid
graph TD
    A[打开 MGMemo] --> B{会写 Markdown 吗?}
    B -- 会 --> C[享受实时预览]
    B -- 不会 --> D[看示例笔记学习]
    D --> C
    C --> E[记录美好生活 ✨]
```

## 🏷️ 标签与搜索

- 顶部搜索栏支持全文搜索
- 点击标签 Chip 快速筛选
- 长按笔记可以删除

> [!TIP]
> 在设置里可以切换主题（浅色 / 深色 / 护眼绿）！
"""

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NotesViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val noteDao = db.noteDao()
    private val historyDao = db.noteHistoryDao()
    private val settingsRepo = SettingsRepository(application)

    // ---------- 列表 ----------
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val searchQuery = MutableStateFlow("")
    val selectedTag = MutableStateFlow<String?>(null)

    val filteredNotes: StateFlow<List<Note>> =
        combine(_notes, searchQuery, selectedTag) { notes, query, tag ->
            notes.filter { note ->
                val matchQuery = query.isBlank() ||
                    note.title.contains(query, ignoreCase = true) ||
                    note.content.contains(query, ignoreCase = true)
                val matchTag = tag == null ||
                    note.tags.split(",").any { it.trim() == tag }
                matchQuery && matchTag
            }
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    val allTags: StateFlow<List<String>> =
        _notes.map { list ->
            list.flatMap { it.tags.split(",").map(String::trim) }
                .filter { it.isNotEmpty() }
                .distinct()
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    // ---------- 编辑 ----------
    private val _currentNoteId = MutableStateFlow<Long?>(null)
    val currentNoteId: StateFlow<Long?> = _currentNoteId.asStateFlow()

    private val _editorContent = MutableStateFlow("")
    val editorContent: StateFlow<String> = _editorContent.asStateFlow()

    private val _currentTitle = MutableStateFlow("")
    val currentTitle: StateFlow<String> = _currentTitle.asStateFlow()

    private val _undoStack = MutableStateFlow<List<String>>(emptyList())
    private val _redoStack = MutableStateFlow<List<String>>(emptyList())
    val undoStack: StateFlow<List<String>> = _undoStack.asStateFlow()
    val redoStack: StateFlow<List<String>> = _redoStack.asStateFlow()

    private val _history = MutableStateFlow<List<NoteHistory>>(emptyList())
    val history: StateFlow<List<NoteHistory>> = _history.asStateFlow()

    // ---------- 保存状态 ----------
    private val _saveStatus = MutableStateFlow("")
    val saveStatus: StateFlow<String> = _saveStatus.asStateFlow()
    private var lastSavedContent = ""

    // ---------- 设置 ----------
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    // ---------- AI 摘要 ----------
    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()
    private val _summaryResult = MutableStateFlow<String?>(null)
    val summaryResult: StateFlow<String?> = _summaryResult.asStateFlow()

    // ---------- 消息 ----------
    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private var restoring = false
    private var pushJob: Job? = null

    init {
        // 首次启动创建示例笔记
        viewModelScope.launch(Dispatchers.IO) {
            if (noteDao.count() == 0) {
                noteDao.insert(
                    Note(
                        title = "欢迎使用 MGMemo 🎉",
                        content = SAMPLE_NOTE,
                        tags = "示例,入门"
                    )
                )
            }
        }

        // 观察笔记列表
        viewModelScope.launch {
            noteDao.observeAll().collect { _notes.value = it }
        }

        // 观察设置
        viewModelScope.launch {
            settingsRepo.settings.collect { _settings.value = it }
        }

        // 观察历史版本（随当前笔记切换）
        viewModelScope.launch {
            _currentNoteId.flatMapLatest { id ->
                if (id == null) flowOf(emptyList()) else historyDao.observeForNote(id)
            }.collect { _history.value = it }
        }

        // 自动保存：500ms 防抖
        viewModelScope.launch {
            _editorContent
                .debounce(500)
                .collectLatest { content -> saveCurrentNote(content) }
        }
    }

    // ================= 列表操作 =================

    fun setSearchQuery(query: String) {
        searchQuery.value = query
    }

    fun setSelectedTag(tag: String?) {
        selectedTag.value = tag
    }

    fun createNote() {
        viewModelScope.launch(Dispatchers.IO) {
            val id = noteDao.insert(
                Note(title = "无标题", content = "", updatedAt = System.currentTimeMillis())
            )
            _currentNoteId.value = id
            _editorContent.value = ""
            lastSavedContent = ""
            _currentTitle.value = "无标题"
            _undoStack.value = emptyList()
            _redoStack.value = emptyList()
            _saveStatus.value = "新笔记"
            NotesWidget.updateWidget(getApplication())
        }
    }

    fun createNoteImported(content: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val title = content.lines().firstOrNull { it.isNotBlank() }?.take(50) ?: "导入的笔记"
            val id = noteDao.insert(
                Note(title = title, content = content, updatedAt = System.currentTimeMillis())
            )
            _currentNoteId.value = id
            _editorContent.value = content
            lastSavedContent = content
            _currentTitle.value = title
            _undoStack.value = emptyList()
            _redoStack.value = emptyList()
            _saveStatus.value = "已导入"
            NotesWidget.updateWidget(getApplication())
        }
    }

    fun openNote(noteId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val note = noteDao.getById(noteId) ?: return@launch
            _currentNoteId.value = noteId
            _editorContent.value = note.content
            lastSavedContent = note.content
            _currentTitle.value = note.title
            _undoStack.value = emptyList()
            _redoStack.value = emptyList()
            _saveStatus.value = ""
        }
    }

    fun closeEditor() {
        viewModelScope.launch(Dispatchers.IO) {
            val id = _currentNoteId.value ?: return@launch
            val note = noteDao.getById(id)
            if (note != null && note.content.isBlank()) {
                noteDao.delete(note)
                historyDao.clearForNote(id)
            }
            _currentNoteId.value = null
            _editorContent.value = ""
            lastSavedContent = ""
            _undoStack.value = emptyList()
            _redoStack.value = emptyList()
        }
    }

    fun deleteNote(noteId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val note = noteDao.getById(noteId) ?: return@launch
            noteDao.delete(note)
            historyDao.clearForNote(noteId)
            if (_currentNoteId.value == noteId) {
                _currentNoteId.value = null
                _editorContent.value = ""
                lastSavedContent = ""
            }
            NotesWidget.updateWidget(getApplication())
        }
    }

    // ================= 编辑操作 =================

    fun onEditorContentChange(newContent: String) {
        if (restoring) {
            _editorContent.value = newContent
            return
        }
        val old = _editorContent.value
        if (newContent == old) return

        // 300ms 防抖入撤销栈（最多 30 步）
        pushJob?.cancel()
        pushJob = viewModelScope.launch {
            delay(300)
            val stack = _undoStack.value.toMutableList()
            if (stack.size >= 30) stack.removeAt(0)
            stack.add(old)
            _undoStack.value = stack
            _redoStack.value = emptyList()
        }
        _editorContent.value = newContent
        _currentTitle.value = newContent.lineSequence()
            .firstOrNull { it.isNotBlank() }?.trim()?.take(50) ?: "无标题"
    }

    fun appendContent(text: String) {
        val current = _editorContent.value
        val new = if (current.isBlank()) text else current.trimEnd() + "\n\n" + text
        onEditorContentChange(new)
    }

    fun undo() {
        val stack = _undoStack.value.toMutableList()
        if (stack.isEmpty()) return
        val prev = stack.removeAt(stack.size - 1)
        _undoStack.value = stack
        val redo = _redoStack.value.toMutableList()
        redo.add(_editorContent.value)
        _redoStack.value = redo
        restoring = true
        _editorContent.value = prev
        lastSavedContent = prev
        _currentTitle.value = prev.lineSequence()
            .firstOrNull { it.isNotBlank() }?.trim()?.take(50) ?: "无标题"
        restoring = false
    }

    fun redo() {
        val stack = _redoStack.value.toMutableList()
        if (stack.isEmpty()) return
        val next = stack.removeAt(stack.size - 1)
        _redoStack.value = stack
        val undo = _undoStack.value.toMutableList()
        undo.add(_editorContent.value)
        _undoStack.value = undo
        restoring = true
        _editorContent.value = next
        lastSavedContent = next
        _currentTitle.value = next.lineSequence()
            .firstOrNull { it.isNotBlank() }?.trim()?.take(50) ?: "无标题"
        restoring = false
    }

    fun restoreHistory(content: String) {
        onEditorContentChange(content)
        lastSavedContent = content
        _saveStatus.value = "已恢复历史版本"
    }

    // ================= 自动保存 + 历史快照 =================

    private suspend fun saveCurrentNote(content: String) {
        if (content == lastSavedContent) return
        val noteId = _currentNoteId.value ?: return
        if (content.isBlank()) return
        val existing = noteDao.getById(noteId) ?: return

        // 内容变化超过 10 字符时记录历史快照
        if (content != existing.content &&
            abs(content.length - existing.content.length) >= 10
        ) {
            historyDao.insert(
                NoteHistory(
                    noteId = noteId,
                    content = existing.content,
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        val updated = existing.copy(
            title = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(50) ?: "无标题",
            content = content,
            updatedAt = System.currentTimeMillis()
        )
        noteDao.update(updated)
        lastSavedContent = content
        _saveStatus.value = "已保存 " + formatTime(System.currentTimeMillis())
        NotesWidget.updateWidget(getApplication())
    }

    // ================= 图片插入 =================

    fun insertImage(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val b64 = compressImageToBase64(uri)
                    ?: run {
                        _toastMessage.value = "图片处理失败"
                        return@launch
                    }
                val markdown = "![](data:image/jpeg;base64,$b64)"
                withContext(Dispatchers.Main) { appendContent(markdown) }
            } catch (e: Exception) {
                _toastMessage.value = "图片插入失败：${e.message}"
            }
        }
    }

    private fun compressImageToBase64(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver

        // 先读取尺寸
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, boundsOpts) }
        val srcW = boundsOpts.outWidth
        val srcH = boundsOpts.outHeight
        if (srcW <= 0 || srcH <= 0) return null

        // 采样避免 OOM
        var sample = 1
        while (srcW / sample > 1440 || srcH / sample > 1440) sample *= 2
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null

        // 压缩到 720p
        val scale = min(1f, 720f / max(bitmap.width, bitmap.height))
        val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, w, h, true)
        if (scaled != bitmap) bitmap.recycle()

        // JPEG 压缩，目标 < 1MB
        var quality = 85
        var bytes: ByteArray
        do {
            val baos = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            bytes = baos.toByteArray()
            quality -= 10
        } while (bytes.size > 1024 * 1024 && quality > 40)
        scaled.recycle()

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ================= AI 摘要 =================

    fun generateSummary() {
        val content = _editorContent.value
        val s = _settings.value
        if (content.isBlank()) {
            _toastMessage.value = "内容为空，无法生成摘要"
            return
        }
        if (s.aiApiUrl.isBlank() || s.aiApiKey.isBlank()) {
            _toastMessage.value = "请先在设置中配置 AI 接口"
            return
        }
        viewModelScope.launch {
            _isSummarizing.value = true
            try {
                val baseUrl = if (s.aiApiUrl.endsWith("/")) s.aiApiUrl else s.aiApiUrl + "/"
                val client = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        val req = chain.request().newBuilder()
                            .header("Authorization", "Bearer ${s.aiApiKey}")
                            .header("Content-Type", "application/json")
                            .build()
                        chain.proceed(req)
                    }
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()
                val api = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(AiApi::class.java)

                val prompt = "请用 50 字以内总结以下内容：\n\n$content"
                val resp = api.chatCompletion(
                    ChatRequest(
                        model = s.aiModel,
                        messages = listOf(ChatMessage(role = "user", content = prompt))
                    )
                )
                val summary = resp.choices.firstOrNull()?.message?.content
                    ?.trim() ?: "（模型未返回内容）"
                _summaryResult.value = summary
            } catch (e: Exception) {
                _toastMessage.value = "AI 请求失败：${e.message}"
            } finally {
                _isSummarizing.value = false
            }
        }
    }

    fun clearSummary() {
        _summaryResult.value = null
    }

    // ================= 导出 =================

    suspend fun exportMarkdown(noteId: Long): Uri? = withContext(Dispatchers.IO) {
        val note = noteDao.getById(noteId) ?: return@withContext null
        val context = getApplication<Application>()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${safeFileName(note.title)}.md")
        file.writeText(note.content)
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    suspend fun exportPdf(noteId: Long): Uri? = withContext(Dispatchers.IO) {
        val note = noteDao.getById(noteId) ?: return@withContext null
        val context = getApplication<Application>()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${safeFileName(note.title)}.pdf")
        try {
            val pageW = 595  // A4 width in points
            val pageH = 842  // A4 height in points
            val contentW = pageW - 100
            val contentH = pageH - 100

            val pdf = PdfDocument()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 28f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val lines = wrapText(note.content, paint, contentW.toFloat())
            var idx = 0
            var pageIndex = 0
            while (idx < lines.size) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, pageIndex + 1).create()
                val page = pdf.startPage(pageInfo)
                val canvas = page.canvas
                var y = 50f
                while (idx < lines.size && y + paint.fontSpacing < contentH) {
                    canvas.drawText(lines[idx], 50f, y, paint)
                    y += paint.fontSpacing
                    idx++
                }
                pdf.finishPage(page)
                pageIndex++
            }
            FileOutputStream(file).use { pdf.writeTo(it) }
            pdf.close()
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Log.e("MGMemo", "PDF 导出失败", e)
            null
        }
    }

    suspend fun exportWord(noteId: Long): Uri? = withContext(Dispatchers.IO) {
        val note = noteDao.getById(noteId) ?: return@withContext null
        val context = getApplication<Application>()
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, "${safeFileName(note.title)}.docx")
        try {
            val html = markdownToHtml(note.content)
            buildDocx(file, html)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            Log.e("MGMemo", "Word 导出失败", e)
            null
        }
    }

    /** 朴素 Markdown → HTML（用于 Word 导出） */
    private fun markdownToHtml(md: String): String {
        val sb = StringBuilder()
        sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>" +
            "body{font-family:'Microsoft YaHei',sans-serif;font-size:11pt;line-height:1.8;padding:40px;color:#333;}" +
            "h1{font-size:18pt;border-bottom:1px solid #ccc;padding-bottom:6px;}" +
            "h2{font-size:14pt;}" +
            "h3{font-size:12pt;}" +
            "code{background:#f4f4f4;padding:1px 4px;border-radius:3px;font-family:Consolas,monospace;}" +
            "pre{background:#f4f4f4;padding:12px;border-radius:6px;white-space:pre-wrap;}" +
            "blockquote{border-left:4px solid #ccc;padding-left:16px;color:#666;margin:12px 0;}" +
            "table{border-collapse:collapse;width:100%;}" +
            "th,td{border:1px solid #ccc;padding:6px 10px;}" +
            "img{max-width:100%;}" +
            "</style></head><body>")
        val lines = md.lines()
        var inCodeBlock = false
        for (line in lines) {
            when {
                line.startsWith("```") -> {
                    if (inCodeBlock) { sb.append("</pre>"); inCodeBlock = false }
                    else { sb.append("<pre>"); inCodeBlock = true }
                }
                inCodeBlock -> sb.append(line).append("\n")
                line.startsWith("# ") -> sb.append("<h1>").append(line.removePrefix("# ")).append("</h1>")
                line.startsWith("## ") -> sb.append("<h2>").append(line.removePrefix("## ")).append("</h2>")
                line.startsWith("### ") -> sb.append("<h3>").append(line.removePrefix("### ")).append("</h3>")
                line.startsWith("> ") -> sb.append("<blockquote>").append(line.removePrefix("> ")).append("</blockquote>")
                line.startsWith("![](") -> sb.append("<p><img src=\"").append(line.substringAfter("(").substringBefore(")")).append("\"></p>")
                line.startsWith("- [ ]") -> sb.append("<p>☐ ").append(line.removePrefix("- [ ] ")).append("</p>")
                line.startsWith("- [x]") -> sb.append("<p>☑ ").append(line.removePrefix("- [x] ")).append("</p>")
                line.startsWith("- ") -> sb.append("<p>• ").append(line.removePrefix("- ")).append("</p>")
                line.isBlank() -> sb.append("<br>")
                else -> sb.append("<p>").append(line).append("</p>")
            }
        }
        if (inCodeBlock) sb.append("</pre>")
        sb.append("</body></html>")
        return sb.toString()
    }

    /** 最小化 DOCX 构建（ZIP + XML + AltChunk） */
    private fun buildDocx(file: File, html: String) {
        val entries = linkedMapOf(
            "[Content_Types].xml" to """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""",
            "_rels/.rels" to """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""",
            "word/_rels/document.xml.rels" to """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="htmlChunk" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/aFChunk" Target="afchunk.mht"/>
</Relationships>""",
            "word/document.xml" to """<?xml version="1.0" encoding="UTF-8"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <w:body>
    <w:altChunk r:id="htmlChunk"/>
  </w:body>
</w:document>""",
            "word/afchunk.mht" to html
        )
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val result = mutableListOf<String>()
        for (para in text.split("\n")) {
            if (para.isEmpty()) {
                result.add("")
                continue
            }
            val sb = StringBuilder()
            for (ch in para) {
                if (sb.isNotEmpty() && paint.measureText(sb.toString() + ch) > maxWidth) {
                    result.add(sb.toString())
                    sb.clear()
                }
                sb.append(ch)
            }
            result.add(sb.toString())
        }
        return result
    }

    private fun safeFileName(title: String): String =
        title.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "untitled" }

    // ================= 设置 =================

    fun updateThemeMode(mode: String) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.updateThemeMode(mode) }
    }

    fun updateEditorLayout(layout: String) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.updateEditorLayout(layout) }
    }

    fun updateBiometric(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.updateBiometric(enabled) }
    }

    fun updateAiConfig(url: String, key: String, model: String) {
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepo.updateAiConfig(url.trim(), key.trim(), model.trim())
        }
    }

    // ================= 消息 =================

    fun consumeToast() {
        _toastMessage.value = null
    }

    companion object {
        fun formatTime(ts: Long): String =
            SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

        fun formatFullTime(ts: Long): String =
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))
    }
}