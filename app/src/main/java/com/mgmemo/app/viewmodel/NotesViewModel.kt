package com.mgmemo.app.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mgmemo.app.data.AiApi
import com.mgmemo.app.data.AppDatabase
import com.mgmemo.app.data.AppSettings
import com.mgmemo.app.data.BackupFile
import com.mgmemo.app.data.BackupManager
import com.mgmemo.app.data.ChatMessage
import com.mgmemo.app.data.ChatRequest
import com.mgmemo.app.data.DocParser
import com.mgmemo.app.data.ExportUtils
import com.mgmemo.app.data.Note
import com.mgmemo.app.data.NoteHistory
import com.mgmemo.app.data.PdfWriter
import com.mgmemo.app.data.SettingsRepository
import com.mgmemo.app.data.WordWriter
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

/** AI 分类改动提案（需用户确认后才真正写入） */
data class ClassifyProposal(
    val noteId: Long,
    val title: String,
    val oldTags: String,
    val newTags: String,
    val category: String
)

/** 导出结果：分享用 Uri + 保存位置说明 */
data class ExportOutcome(
    val uri: Uri,
    val fileName: String,
    val inDownloads: Boolean
) {
    val locationLabel: String
        get() = if (inDownloads) "已保存到「下载/MGMemo」" else "已生成（缓存目录）"
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class NotesViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val noteDao = db.noteDao()
    private val historyDao = db.noteHistoryDao()
    private val settingsRepo = SettingsRepository(application)

    // ---------- 列表 ----------
    private val _notes = MutableStateFlow<List<Note>>(emptyList())

    /** 全部笔记（标签管理页使用，不受搜索/筛选影响） */
    val allNotes: StateFlow<List<Note>> = _notes.asStateFlow()
    val searchQuery = MutableStateFlow("")
    val selectedTag = MutableStateFlow<String?>(null)

    // ---------- 设置（需在 allTags 之前声明：属性按声明顺序初始化） ----------
    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

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
        combine(_notes, _settings) { list, s ->
            val fromNotes = list.flatMap { it.tags.split(",").map(String::trim) }
            val custom = s.customTags.split(",").map(String::trim)
            (fromNotes + custom)
                .filter { it.isNotEmpty() }
                .distinct()
                .sorted()
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    /** 每个标签对应的笔记数量 */
    val tagCounts: StateFlow<Map<String, Int>> =
        _notes.map { list ->
            val map = mutableMapOf<String, Int>()
            list.forEach { note ->
                note.tags.split(",").map(String::trim)
                    .filter { it.isNotEmpty() }
                    .distinct()
                    .forEach { tag -> map[tag] = (map[tag] ?: 0) + 1 }
            }
            map
        }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyMap())

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
    // （_settings 已在上方「列表」区域提前声明，供 allTags 使用）

    // ---------- AI 摘要 ----------
    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()
    private val _summaryResult = MutableStateFlow<String?>(null)
    val summaryResult: StateFlow<String?> = _summaryResult.asStateFlow()
    /** 当前展示的摘要是否来自本地缓存 */
    private val _summaryFromCache = MutableStateFlow(false)
    val summaryFromCache: StateFlow<Boolean> = _summaryFromCache.asStateFlow()

    // ---------- AI 起标题 ----------
    private val _isTitling = MutableStateFlow(false)
    val isTitling: StateFlow<Boolean> = _isTitling.asStateFlow()

    // ---------- 标签 ----------
    private val _currentTags = MutableStateFlow("")
    val currentTags: StateFlow<String> = _currentTags.asStateFlow()
    private val _isSuggestingTags = MutableStateFlow(false)
    val isSuggestingTags: StateFlow<Boolean> = _isSuggestingTags.asStateFlow()
    private val _suggestedTags = MutableStateFlow<List<String>>(emptyList())
    val suggestedTags: StateFlow<List<String>> = _suggestedTags.asStateFlow()

    // ---------- AI 智能分类 ----------
    private val _isClassifying = MutableStateFlow(false)
    val isClassifying: StateFlow<Boolean> = _isClassifying.asStateFlow()
    private val _classifyResult = MutableStateFlow<String?>(null)
    val classifyResult: StateFlow<String?> = _classifyResult.asStateFlow()

    /** AI 分类提案（用户确认后才写入数据库） */
    private val _classifyProposals = MutableStateFlow<List<ClassifyProposal>>(emptyList())
    val classifyProposals: StateFlow<List<ClassifyProposal>> = _classifyProposals.asStateFlow()

    /** AI 标题建议（用于弹窗内展示，不直接落库） */
    private val _titleSuggestion = MutableStateFlow<String?>(null)
    val titleSuggestion: StateFlow<String?> = _titleSuggestion.asStateFlow()

    /** 已推荐的候选标签（供用户二次选择，不重复请求 AI） */
    private var cachedCandidateTags: List<String> = emptyList()

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
            _currentTags.value = ""
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
            _currentTags.value = ""
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
            _currentTags.value = note.tags
            _suggestedTags.value = emptyList()
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
            title = resolveTitle(existing, content),
            content = content,
            updatedAt = System.currentTimeMillis()
        )
        noteDao.update(updated)
        lastSavedContent = content
        _saveStatus.value = "已保存 " + formatTime(System.currentTimeMillis())
        NotesWidget.updateWidget(getApplication())
    }

    /**
     * 标题解析：
     * - 若原标题是「自动从正文首行派生」的，跟随正文更新；
     * - 若标题由 AI 生成或用户手动指定，则保持不被覆盖。
     */
    private fun resolveTitle(existing: Note, content: String): String {
        val derived = content.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.take(50) ?: "无标题"
        val existingDerived = existing.content.lineSequence().firstOrNull { it.isNotBlank() }
            ?.trim()?.take(50) ?: "无标题"
        val wasAuto = existing.title.isBlank() ||
            existing.title == "无标题" ||
            existing.title == existingDerived
        return if (wasAuto) derived else existing.title
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

    // ================= AI 能力 =================

    /** 构建 AI 接口（未配置返回 null） */
    private fun buildAiApi(): AiApi? {
        val s = _settings.value
        if (s.aiApiUrl.isBlank() || s.aiApiKey.isBlank()) return null
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
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AiApi::class.java)
    }

    /** 统一 AI 调用入口 */
    private suspend fun callAi(prompt: String): String? {
        val api = buildAiApi() ?: run {
            _toastMessage.value = "请先在设置中配置 AI 接口"
            return null
        }
        return try {
            val resp = api.chatCompletion(
                ChatRequest(
                    model = _settings.value.aiModel,
                    messages = listOf(ChatMessage(role = "user", content = prompt))
                )
            )
            resp.choices.firstOrNull()?.message?.content?.trim()
        } catch (e: Exception) {
            _toastMessage.value = "AI 请求失败：${e.message}"
            null
        }
    }

    // ---------- AI 摘要（生成一次即持久化） ----------

    /**
     * 点击 AI 摘要：本地已有摘要则直接展示（不重复请求），否则生成并保存。
     * @param force true = 强制重新生成
     */
    fun generateSummary(force: Boolean = false) {
        val id = _currentNoteId.value
        if (id == null) {
            _toastMessage.value = "请先打开一篇笔记"
            return
        }
        viewModelScope.launch {
            // 1. 优先使用已保存的摘要
            if (!force) {
                val saved = withContext(Dispatchers.IO) { noteDao.getById(id)?.summary }.orEmpty()
                if (saved.isNotBlank()) {
                    _summaryFromCache.value = true
                    _summaryResult.value = saved
                    return@launch
                }
            }
            val content = _editorContent.value
            if (content.isBlank()) {
                _toastMessage.value = "内容为空，无法生成摘要"
                return@launch
            }
            _isSummarizing.value = true
            _summaryFromCache.value = false
            try {
                val prompt = "请用 80 字以内总结以下笔记内容，直接输出摘要本身，不要任何前缀或解释：\n\n" +
                    content.take(6000)
                val summary = callAi(prompt)
                if (!summary.isNullOrBlank()) {
                    _summaryResult.value = summary
                    withContext(Dispatchers.IO) {
                        noteDao.updateSummary(id, summary, System.currentTimeMillis())
                    }
                    _saveStatus.value = "摘要已保存"
                }
            } finally {
                _isSummarizing.value = false
            }
        }
    }

    fun clearSummary() {
        _summaryResult.value = null
        _summaryFromCache.value = false
    }

    // ---------- AI 起标题 ----------

    /** 请求 AI 生成标题（不落库，只返回结果） */
    private suspend fun requestAiTitle(content: String): String? {
        val prompt = "请为下面这篇笔记起一个简洁、准确的标题。" +
            "要求：不超过 20 个字，只输出标题本身，不要引号、不要句号、不要任何解释。\n\n" +
            content.take(4000)
        val title = sanitizeTitle(callAi(prompt))
        return title.ifBlank { null }
    }

    /** 工具栏「AI标题」：生成后直接应用并落库 */
    fun generateTitleWithAi() {
        val content = _editorContent.value
        if (content.isBlank()) {
            _toastMessage.value = "内容为空，无法生成标题"
            return
        }
        viewModelScope.launch {
            _isTitling.value = true
            try {
                val title = requestAiTitle(content)
                if (title != null) {
                    _currentTitle.value = title
                    val id = _currentNoteId.value
                    if (id != null) {
                        withContext(Dispatchers.IO) {
                            noteDao.updateTitle(id, title, System.currentTimeMillis())
                        }
                    }
                    _toastMessage.value = "标题已由 AI 生成"
                }
            } finally {
                _isTitling.value = false
            }
        }
    }

    /** 标题弹窗内「让 AI 帮我起标题」：只产出建议，弹窗保持打开 */
    fun suggestTitleWithAi() {
        val content = _editorContent.value
        if (content.isBlank()) {
            _toastMessage.value = "内容为空，无法生成标题"
            return
        }
        viewModelScope.launch {
            _isTitling.value = true
            try {
                _titleSuggestion.value = requestAiTitle(content)
            } finally {
                _isTitling.value = false
            }
        }
    }

    fun clearTitleSuggestion() {
        _titleSuggestion.value = null
    }

    /** 手动设置标题 */
    fun setTitle(title: String) {
        val t = title.trim().take(60)
        if (t.isBlank()) return
        _currentTitle.value = t
        val id = _currentNoteId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            noteDao.updateTitle(id, t, System.currentTimeMillis())
        }
    }

    /** 清洗 AI 返回的标题 */
    private fun sanitizeTitle(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.lines().firstOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
            .removePrefix("标题：")
            .removePrefix("标题:")
            .trim()
            .trim('"', '\'', '“', '”', '#', '*', '`', '。', '，', ',', '.', ':', '：')
            .trim()
            .take(60)
    }

    // ---------- 标签 ----------

    /** 保存标签（逗号分隔） */
    fun updateTags(tags: String) {
        _currentTags.value = tags
        val id = _currentNoteId.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            noteDao.updateTags(id, tags, System.currentTimeMillis())
            _saveStatus.value = "标签已更新"
        }
    }

    /** 让 AI 结合「全部笔记标题 + 本篇内容」推荐一批标签供选择 */
    fun suggestTagsWithAi() {
        viewModelScope.launch {
            _isSuggestingTags.value = true
            try {
                val all = withContext(Dispatchers.IO) { noteDao.getAll() }
                val overview = all.take(30).joinToString("\n") { "- ${it.title}" }
                val current = ExportUtils.markdownToPlainText(_editorContent.value).take(1500)
                val existing = existingTags(all)
                val strict = _settings.value.aiTagStrict && existing.isNotEmpty()

                val prompt = if (strict) {
                    """你是笔记整理助手。请从下面给出的「现有标签」中，为当前笔记挑选 6 个最合适的标签。
【必须遵守】只能使用现有标签，且标签名必须与列表中的写法完全一致，绝对不要创造新标签。
如果现有标签都不合适，就少给几个或一个都不给，不要编造。
【现有标签】
${existing.joinToString("、")}
【当前笔记内容】
$current
要求：只输出标签本身，用英文逗号分隔，不要解释、不要序号、不要 # 号。"""
                } else {
                    """你是笔记整理助手。请阅读下面所有笔记的标题，再结合当前笔记内容，
给出 6 个最合适的标签（既贴合已有分类习惯，又能覆盖新内容）。
要求：只输出标签本身，用英文逗号分隔，不要解释、不要序号、不要 # 号，每个标签 2-6 个字。
【现有标签（可参考）】
${existing.joinToString("、")}
【全部笔记标题】
$overview
【当前笔记内容】
$current"""
                }

                var tags = parseTagList(callAi(prompt))
                if (strict) {
                    val allowed = existing.toSet()
                    // 严格模式兜底：AI 硬造的新标签一律丢弃
                    tags = tags.filter { it in allowed }
                }
                if (tags.isNotEmpty()) {
                    cachedCandidateTags = tags
                    _suggestedTags.value = tags
                } else {
                    _toastMessage.value = if (strict) {
                        "现有标签中没有合适的，可关闭「AI 只用现有标签」再试"
                    } else {
                        "AI 未返回可用标签"
                    }
                }
            } finally {
                _isSuggestingTags.value = false
            }
        }
    }

    /** 现有标签集合：笔记里用过的 + 手动创建的 */
    private fun existingTags(all: List<Note>): List<String> {
        val fromNotes = all.flatMap { it.tags.split(",").map(String::trim) }
        val custom = _settings.value.customTags.split(",").map(String::trim)
        return (fromNotes + custom).filter { it.isNotEmpty() }.distinct().sorted()
    }

    private fun parseTagList(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.replace("\n", ",")
            .split(",", "，", "、", ";", "；")
            .map { it.trim().trim('#', '-', '*', '`', '"', '\'', '.', '1', '2', '3', '4', '5', '6', '7', '8', '9', '0') }
            .filter { it.isNotBlank() && it.length <= 12 }
            .distinct()
            .take(12)
    }

    // ---------- AI 一键分类 ----------

    /** 让 AI 读取全部笔记，生成分类提案（不直接改标签，等用户确认） */
    fun classifyNotesWithAi() {
        viewModelScope.launch {
            _isClassifying.value = true
            try {
                val all = withContext(Dispatchers.IO) { noteDao.getAll() }
                if (all.isEmpty()) {
                    _toastMessage.value = "还没有笔记可以分类"
                    return@launch
                }
                val listing = all.take(200).joinToString("\n") { n ->
                    "[${n.id}] ${n.title} :: ${plainSnippet(n.content)}"
                }
                val existing = existingTags(all)
                val strict = _settings.value.aiTagStrict && existing.isNotEmpty()

                val tagRule = if (strict) {
                    """【必须遵守】只能使用下面列出的「现有标签」，标签名必须与列表中的写法完全一致，
绝对不要创造新标签；某条笔记如果确实无法归入现有标签，就不要给它分配标签（省略该条）。"""
                } else {
                    """分类名请自行归纳，每类 2-6 个字。"""
                }

                val prompt = if (strict) {
                    """你是笔记分类助手。请把下面的笔记按主题归入现有标签。
$tagRule

【现有标签】
${existing.joinToString("、")}

只输出 JSON 数组，格式：[{"id":1,"tag":"工作"},{"id":2,"tag":"生活"}]
不要输出任何解释文字。

笔记列表：
$listing"""
                } else {
                    """你是笔记分类助手。请把下面的笔记按主题分成若干类别（建议 3-8 类），
为每条笔记指定一个最合适的类别名（2-6 个字）。
只输出 JSON 数组，格式：[{"id":1,"tag":"工作"},{"id":2,"tag":"生活"}]
不要输出任何解释文字。

笔记列表：
$listing"""
                }

                var mapping = parseClassifyResult(callAi(prompt))

                // 严格模式兜底：AI 硬造出来的标签会被过滤掉
                if (strict) {
                    val allowed = existing.toSet()
                    mapping = mapping.filterValues { it in allowed }
                }

                if (mapping.isEmpty()) {
                    _toastMessage.value = if (strict) {
                        "AI 没能把笔记归入现有标签，可关闭「AI 只用现有标签」再试"
                    } else {
                        "AI 未返回可用的分类结果"
                    }
                    return@launch
                }

                // 生成「只追加、不删除原有标签」的改动提案
                val proposals = all.mapNotNull { note ->
                    val category = mapping[note.id] ?: return@mapNotNull null
                    val existing = note.tags.split(",").map { it.trim() }
                        .filter { it.isNotBlank() }
                    if (existing.contains(category)) return@mapNotNull null // 已有该标签，无需改动
                    val merged = (existing + category).joinToString(",")
                    ClassifyProposal(
                        noteId = note.id,
                        title = note.title,
                        oldTags = existing.joinToString(","),
                        newTags = merged,
                        category = category
                    )
                }

                if (proposals.isEmpty()) {
                    _classifyResult.value = "所有笔记都已包含合适的标签，无需改动。"
                } else {
                    _classifyProposals.value = proposals
                }
            } finally {
                _isClassifying.value = false
            }
        }
    }

    /** 应用选中的分类提案（保留原有标签，仅追加 AI 类别） */
    fun applyClassifyProposals(selectedIds: Set<Long>) {
        val proposals = _classifyProposals.value.filter { it.noteId in selectedIds }
        if (proposals.isEmpty()) {
            _classifyProposals.value = emptyList()
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                proposals.forEach { p -> noteDao.updateTags(p.noteId, p.newTags, now) }
            }
            val grouped = proposals.groupingBy { it.category }.eachCount()
                .entries.sortedByDescending { it.value }
            _classifyResult.value = "已为 ${proposals.size} 篇笔记追加标签：\n\n" +
                grouped.joinToString("、") { "${it.key}（${it.value} 篇）" }
            _classifyProposals.value = emptyList()
            // 同步当前编辑笔记的标签
            _currentNoteId.value?.let { id ->
                proposals.firstOrNull { it.noteId == id }?.let { _currentTags.value = it.newTags }
            }
        }
    }

    fun dismissClassifyProposals() {
        _classifyProposals.value = emptyList()
    }

    fun clearClassifyResult() {
        _classifyResult.value = null
    }

    /** 清空已推荐的候选标签 */
    fun clearSuggestedTags() {
        _suggestedTags.value = emptyList()
    }

    // ---------- 标签管理（统一入口：新建 / 重命名 / 删除 / 绑定笔记） ----------

    /** 新建标签（即使暂未绑定任何笔记也会保留） */
    fun createTag(name: String) {
        val t = name.trim().trimStart('#').take(20)
        if (t.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val current = _settings.value.customTags.split(",")
                .map { it.trim() }.filter { it.isNotBlank() }
            if (!current.contains(t)) {
                settingsRepo.updateCustomTags((current + t).joinToString(","))
                _toastMessage.value = "已创建标签 #$t"
            }
        }
    }

    /** 重命名标签：同步更新所有引用它的笔记 */
    fun renameTag(oldName: String, newName: String) {
        val t = newName.trim().trimStart('#').take(20)
        if (t.isBlank() || t == oldName) return
        viewModelScope.launch(Dispatchers.IO) {
            val all = noteDao.getAll()
            val now = System.currentTimeMillis()
            all.forEach { note ->
                val tags = note.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (tags.contains(oldName)) {
                    val updated = tags.map { if (it == oldName) t else it }
                        .distinct().joinToString(",")
                    noteDao.updateTags(note.id, updated, now)
                }
            }
            val custom = _settings.value.customTags.split(",")
                .map { it.trim() }.filter { it.isNotBlank() }
            settingsRepo.updateCustomTags(
                custom.map { if (it == oldName) t else it }.distinct().joinToString(",")
            )
            refreshCurrentTags()
            _toastMessage.value = "标签已重命名为 #$t"
        }
    }

    /** 删除标签：从所有笔记与自定义标签中移除 */
    fun deleteTag(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val all = noteDao.getAll()
            val now = System.currentTimeMillis()
            all.forEach { note ->
                val tags = note.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
                if (tags.contains(name)) {
                    noteDao.updateTags(
                        note.id,
                        tags.filter { it != name }.distinct().joinToString(","),
                        now
                    )
                }
            }
            val custom = _settings.value.customTags.split(",")
                .map { it.trim() }.filter { it.isNotBlank() }
            settingsRepo.updateCustomTags(custom.filter { it != name }.joinToString(","))
            refreshCurrentTags()
            _toastMessage.value = "已删除标签 #$name"
        }
    }

    /** 把标签绑定 / 解绑到某篇笔记 */
    fun toggleTagOnNote(tag: String, noteId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val note = noteDao.getById(noteId) ?: return@launch
            val tags = note.tags.split(",").map { it.trim() }
                .filter { it.isNotBlank() }.toMutableList()
            if (tags.contains(tag)) tags.remove(tag) else tags.add(tag)
            val joined = tags.distinct().joinToString(",")
            noteDao.updateTags(noteId, joined, System.currentTimeMillis())
            if (_currentNoteId.value == noteId) _currentTags.value = joined
        }
    }

    /** 重新读取当前笔记的标签（标签被全局改名 / 删除后同步） */
    private suspend fun refreshCurrentTags() {
        val id = _currentNoteId.value ?: return
        val n = noteDao.getById(id) ?: return
        _currentTags.value = n.tags
    }

    /** 解析 AI 返回的 JSON 分类结果（容错） */
    private fun parseClassifyResult(raw: String?): Map<Long, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val start = raw.indexOf('[')
        val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyMap()
        val json = raw.substring(start, end + 1)
        val arr = try {
            com.google.gson.JsonParser.parseString(json).asJsonArray
        } catch (e: Exception) {
            null
        } ?: return emptyMap()
        val map = linkedMapOf<Long, String>()
        for (el in arr) {
            try {
                val obj = el.asJsonObject
                val id = obj.get("id")?.asLong ?: continue
                val tag = obj.get("tag")?.asString?.trim().orEmpty()
                if (tag.isNotBlank()) map[id] = tag
            } catch (_: Exception) {
                // 忽略单条解析失败
            }
        }
        return map
    }

    /** 纯文本片段（给 AI 看，避免 Base64 占满 token） */
    private fun plainSnippet(content: String): String =
        ExportUtils.markdownToPlainText(content).replace("\n", " ").take(60)

    /** 分享纯文本：剥离全部 Markdown / HTML 标记 */
    fun plainTextForShare(): String = ExportUtils.markdownToPlainText(_editorContent.value)

    // ================= 导出（默认保存到系统「下载/MGMemo」） =================

    private val docxMime =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    suspend fun exportMarkdown(noteId: Long): ExportOutcome? = withContext(Dispatchers.IO) {
        val note = noteDao.getById(noteId) ?: return@withContext null
        val context = getApplication<Application>()
        val fileName = "${safeFileName(note.title)}.md"
        val bytes = note.content.toByteArray(Charsets.UTF_8)
        val saved = ExportUtils.saveToDownloads(context, fileName, "text/markdown") {
            it.write(bytes)
        }
        saved.uri?.let { ExportOutcome(it, fileName, saved.inDownloads) }
    }

    /**
     * 导出 PDF：结构化解析 Markdown/HTML → 原生 Canvas 绘制 → 写入「下载/MGMemo」。
     * 完全不走 WebView，避免出现空白页。
     */
    suspend fun exportPdf(noteId: Long): ExportOutcome? = withContext(Dispatchers.IO) {
        val note = noteDao.getById(noteId) ?: return@withContext null
        val context = getApplication<Application>()
        val fileName = "${safeFileName(note.title)}.pdf"
        try {
            val blocks = DocParser.parse(note.content)
            val saved = ExportUtils.saveToDownloads(context, fileName, "application/pdf") { out ->
                PdfWriter.write(blocks, out)
            }
            saved.uri?.let { ExportOutcome(it, fileName, saved.inDownloads) }
        } catch (e: Exception) {
            Log.e("MGMemo", "PDF 导出失败", e)
            null
        }
    }

    /** 导出 Word：结构化解析 → 真正的 WordprocessingML → 写入「下载/MGMemo」 */
    suspend fun exportWord(noteId: Long): ExportOutcome? = withContext(Dispatchers.IO) {
        val note = noteDao.getById(noteId) ?: return@withContext null
        val context = getApplication<Application>()
        val fileName = "${safeFileName(note.title)}.docx"
        try {
            val blocks = DocParser.parse(note.content)
            val saved = ExportUtils.saveToDownloads(context, fileName, docxMime) { out ->
                WordWriter.build(out, blocks)
            }
            saved.uri?.let { ExportOutcome(it, fileName, saved.inDownloads) }
        } catch (e: Exception) {
            Log.e("MGMemo", "Word 导出失败", e)
            null
        }
    }

    // ================= 备份与恢复 =================

    private val _isBackingUp = MutableStateFlow(false)
    val isBackingUp: StateFlow<Boolean> = _isBackingUp.asStateFlow()

    /** 备份/恢复结果提示（UI 弹窗或 Toast 后清除） */
    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    /** 最近一次导出的备份文件（可用于分享） */
    private val _lastBackupUri = MutableStateFlow<Uri?>(null)
    val lastBackupUri: StateFlow<Uri?> = _lastBackupUri.asStateFlow()

    /** 已解析、等待用户选择导入方式的数据 */
    private val _pendingImport = MutableStateFlow<BackupFile?>(null)
    val pendingImport: StateFlow<BackupFile?> = _pendingImport.asStateFlow()

    /** 导出全部笔记为 JSON 备份，保存到「下载/MGMemo」 */
    fun exportBackup() {
        viewModelScope.launch {
            _isBackingUp.value = true
            try {
                val payload = withContext(Dispatchers.IO) {
                    val notes = noteDao.getAll()
                    // 先一次性取出历史（suspend 不能放在普通 lambda 里）
                    val historyMap = notes.associate { it.id to historyDao.listForNote(it.id) }
                    BackupManager.build(
                        notes = notes,
                        historyOf = { id -> historyMap[id].orEmpty() },
                        settings = _settings.value
                    )
                }
                val json = BackupManager.toJson(payload)
                val context = getApplication<Application>()
                val name = BackupManager.fileName()
                val saved = withContext(Dispatchers.IO) {
                    ExportUtils.saveToDownloads(context, name, "application/json") { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    }
                }
                _lastBackupUri.value = saved.uri
                _backupMessage.value = when {
                    saved.uri == null -> "备份失败，请重试"
                    saved.inDownloads ->
                        "已备份 ${payload.notes.size} 篇笔记\n下载/MGMemo/$name"
                    else -> "已生成备份文件 $name\n（可点「分享备份」另存）"
                }
            } catch (e: Exception) {
                Log.e("MGMemo", "备份失败", e)
                _backupMessage.value = "备份失败：${e.message}"
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    /** 读取用户选中的备份文件，解析后暂存，等待确认导入方式 */
    fun prepareImport(uri: Uri) {
        viewModelScope.launch {
            _isBackingUp.value = true
            try {
                val context = getApplication<Application>()
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                }.orEmpty()
                BackupManager.parse(text)
                    .onSuccess { _pendingImport.value = it }
                    .onFailure { e ->
                        _backupMessage.value = "导入失败：${e.message ?: "文件无法解析"}"
                    }
            } catch (e: Exception) {
                Log.e("MGMemo", "读取备份失败", e)
                _backupMessage.value = "导入失败：${e.message}"
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    /**
     * 执行导入。
     * @param replace true = 清空现有笔记后覆盖导入；false = 追加合并
     */
    fun confirmImport(replace: Boolean) {
        val payload = _pendingImport.value ?: return
        _pendingImport.value = null
        viewModelScope.launch {
            _isBackingUp.value = true
            try {
                val count = withContext(Dispatchers.IO) {
                    if (replace) {
                        noteDao.deleteAll()
                        historyDao.deleteAll()
                    }
                    var inserted = 0
                    payload.notes.forEach { bn ->
                        val now = System.currentTimeMillis()
                        val id = noteDao.insert(
                            Note(
                                title = bn.title.ifBlank { "无标题" },
                                content = bn.content,
                                tags = bn.tags,
                                summary = bn.summary,
                                createdAt = if (bn.createdAt > 0) bn.createdAt else now,
                                updatedAt = if (bn.updatedAt > 0) bn.updatedAt else now
                            )
                        )
                        if (bn.history.isNotEmpty()) {
                            historyDao.insertAll(
                                bn.history.map {
                                    NoteHistory(
                                        noteId = id,
                                        content = it.content,
                                        timestamp = if (it.timestamp > 0) it.timestamp else now
                                    )
                                }
                            )
                        }
                        inserted++
                    }
                    // 恢复非敏感设置（API Key 不在备份内，保持本机现有配置）
                    payload.settings?.let { st ->
                        if (st.themeMode.isNotBlank()) settingsRepo.updateThemeMode(st.themeMode)
                        if (st.editorLayout.isNotBlank()) {
                            settingsRepo.updateEditorLayout(st.editorLayout)
                        }
                        settingsRepo.updateCustomTags(st.customTags)
                    }
                    inserted
                }
                NotesWidget.updateWidget(getApplication())
                _backupMessage.value = if (replace) {
                    "已覆盖导入 $count 篇笔记"
                } else {
                    "已合并导入 $count 篇笔记"
                }
            } catch (e: Exception) {
                Log.e("MGMemo", "导入失败", e)
                _backupMessage.value = "导入失败：${e.message}"
            } finally {
                _isBackingUp.value = false
            }
        }
    }

    fun dismissPendingImport() {
        _pendingImport.value = null
    }

    fun clearBackupMessage() {
        _backupMessage.value = null
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

    /** AI 分类 / 推荐标签是否强制只使用已有标签 */
    fun updateAiTagStrict(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { settingsRepo.updateAiTagStrict(enabled) }
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