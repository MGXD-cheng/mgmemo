package com.mgmemo.app.ui.screens

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.mgmemo.app.ui.components.HistoryDialog
import com.mgmemo.app.ui.components.MarkdownWebView
import com.mgmemo.app.viewmodel.NotesViewModel
import java.io.File
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    viewModel: NotesViewModel,
    onBack: () -> Unit
) {
    val content by viewModel.editorContent.collectAsState()
    val title by viewModel.currentTitle.collectAsState()
    val undoStack by viewModel.undoStack.collectAsState()
    val redoStack by viewModel.redoStack.collectAsState()
    val saveStatus by viewModel.saveStatus.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val history by viewModel.history.collectAsState()
    val isSummarizing by viewModel.isSummarizing.collectAsState()
    val summaryResult by viewModel.summaryResult.collectAsState()
    val currentNoteId by viewModel.currentNoteId.collectAsState()

    // ---------- 编辑页布局 ----------
    // 取值：preview（仅渲染预览，默认）/ edit（仅原文，按钮临时切换）/ split（双栏）/ wysiwyg（所见即所得编辑）
    val defaultLayout = settings.editorLayout
    var layout by remember { mutableStateOf(defaultLayout) }
    LaunchedEffect(defaultLayout) {
        if (layout != "edit" && layout != "wysiwyg") layout = defaultLayout
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showHistory by remember { mutableStateOf(false) }
    var showExportMenu by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }

    // ---------- 滚动同步状态 ----------
    val textScroll = rememberScrollState()
    var previewRatio by remember { mutableFloatStateOf(0f) }
    var syncing by remember { mutableStateOf(false) }
    var dividerRatio by remember { mutableFloatStateOf(0.5f) }
    var rowWidth by remember { mutableFloatStateOf(1000f) }

    // 编辑区 → 预览区
    LaunchedEffect(textScroll.value) {
        if (!syncing && textScroll.maxValue > 0) {
            previewRatio = textScroll.value.toFloat() / textScroll.maxValue
        }
    }

    // 预览区滚动 → 驱动编辑区
    LaunchedEffect(previewRatio) {
        if (!syncing && previewRatio > 0f) {
            val max = textScroll.maxValue
            if (max > 0) {
                syncing = true
                textScroll.scrollTo((max * previewRatio).toInt())
                syncing = false
            }
        }
    }

    // ---------- 图片 / 相机 / 语音 ----------
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.insertImage(it) } }

    val cameraUri = remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        cameraUri.value?.let { if (success) viewModel.insertImage(it) }
    }

    fun createCameraUri(): Uri {
        val dir = File(context.cacheDir, "images").apply { mkdirs() }
        val file = File(dir, "camera_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    // 系统语音识别 Intent（华为等设备无系统 RecognitionService 时的降级方案）
    val voiceIntentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val text = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            viewModel.appendContent("> [!语音]\n> $text")
        } else {
            Toast.makeText(context, "未能识别语音", Toast.LENGTH_SHORT).show()
        }
    }

    fun startVoiceByIntent() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "对着话筒说话…")
        }
        try {
            voiceIntentLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "未找到可用的语音识别服务", Toast.LENGTH_SHORT).show()
        }
    }

    fun startVoiceInput() {
        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (!matches.isNullOrEmpty()) {
                        viewModel.appendContent("> [!语音]\n> ${matches[0]}")
                    } else {
                        Toast.makeText(context, "未能识别语音", Toast.LENGTH_SHORT).show()
                    }
                    recognizer.destroy()
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    if (error == SpeechRecognizer.ERROR_CLIENT ||
                        error == SpeechRecognizer.ERROR_SERVER ||
                        error == SpeechRecognizer.ERROR_NETWORK
                    ) {
                        // 无法绑定识别服务（华为等无系统 RecognitionService 的设备常见）
                        // → 降级到系统语音识别界面（华为会路由到华为语音/讯飞）
                        startVoiceByIntent()
                    } else {
                        Toast.makeText(context, "语音识别失败（$error）", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizer.startListening(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "语音识别不可用：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val speechPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                startVoiceInput()
            } else {
                startVoiceByIntent()
            }
        } else {
            Toast.makeText(context, "需要录音权限才能使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 导出 / 分享 ----------
    fun shareUri(uri: Uri, mime: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享文件"))
    }

    fun doExportMarkdown() {
        val id = currentNoteId ?: return
        scope.launch {
            val uri = viewModel.exportMarkdown(id)
            if (uri != null) shareUri(uri, "text/markdown")
            else Toast.makeText(context, "导出失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun doExportPdf() {
        val id = currentNoteId ?: return
        scope.launch {
            val uri = viewModel.exportPdf(id)
            if (uri != null) shareUri(uri, "application/pdf")
            else Toast.makeText(context, "导出 PDF 失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun doExportWord() {
        val id = currentNoteId ?: return
        scope.launch {
            val uri = viewModel.exportWord(id)
            if (uri != null) shareUri(uri, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
            else Toast.makeText(context, "导出 Word 失败", Toast.LENGTH_SHORT).show()
        }
    }

    fun doShareText() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, content)
        }
        context.startActivity(Intent.createChooser(send, "分享纯文本"))
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    },
                    title = {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    actions = {
                        IconButton(onClick = { showHistory = true }) {
                            Icon(
                                imageVector = Icons.Default.DateRange,
                                contentDescription = "历史版本"
                            )
                        }
                        Box {
                            IconButton(onClick = { showExportMenu = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "更多操作"
                                )
                            }
                            DropdownMenu(
                                expanded = showExportMenu,
                                onDismissRequest = { showExportMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("导出 .md") },
                                    onClick = {
                                        showExportMenu = false
                                        doExportMarkdown()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("导出 PDF") },
                                    onClick = {
                                        showExportMenu = false
                                        doExportPdf()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("导出 Word (.docx)") },
                                    onClick = {
                                        showExportMenu = false
                                        doExportWord()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("分享纯文本") },
                                    onClick = {
                                        showExportMenu = false
                                        doShareText()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("删除笔记") },
                                    onClick = {
                                        showExportMenu = false
                                        showDeleteConfirm = true
                                    }
                                )
                            }
                        }
                    }
                )

                // 编辑工具栏
                EditorToolbar(
                    isPreview = layout != "edit" && layout != "wysiwyg",
                    onToggleLayout = {
                        layout = when (layout) {
                            "edit" -> defaultLayout
                            "wysiwyg" -> defaultLayout
                            else -> "edit"
                        }
                    },
                    onToggleWysiwyg = {
                        layout = if (layout == "wysiwyg") defaultLayout else "wysiwyg"
                    },
                    canUndo = undoStack.isNotEmpty(),
                    canRedo = redoStack.isNotEmpty(),
                    isSummarizing = isSummarizing,
                    onUndo = viewModel::undo,
                    onRedo = viewModel::redo,
                    onImage = { imagePicker.launch("image/*") },
                    onCamera = {
                        val uri = createCameraUri()
                        cameraUri.value = uri
                        cameraLauncher.launch(uri)
                    },
                    onVoice = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (!granted) {
                            speechPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else if (SpeechRecognizer.isRecognitionAvailable(context)) {
                            startVoiceInput()
                        } else {
                            startVoiceByIntent()
                        }
                    },
                    onSummarize = viewModel::generateSummary
                )

                // 保存状态
                if (saveStatus.isNotEmpty()) {
                    Text(
                        text = saveStatus,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }
    ) { padding ->
        // ---------- 编辑页主体（按布局模式渲染）----------
        when (layout) {
            "edit" -> {
                // 仅原文（未渲染的 Markdown，可编辑）
                EditorTextPane(
                    content = content,
                    onContentChange = viewModel::onEditorContentChange,
                    textScroll = textScroll,
                    modifier = Modifier.padding(padding).fillMaxSize()
                )
            }
            "preview" -> {
                // 仅渲染预览
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    MarkdownWebView(
                        markdown = content,
                        themeMode = settings.themeMode,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            "wysiwyg" -> {
                // 所见即所得编辑：渲染后可直接修改
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    MarkdownWebView(
                        markdown = content,
                        themeMode = settings.themeMode,
                        editable = true,
                        onContentChanged = { md -> viewModel.onEditorContentChange(md) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            else -> {
                // 双栏实时预览
                Row(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .onSizeChanged { rowWidth = it.width.toFloat() }
                ) {
            // 左侧：编辑器
            Box(
                modifier = Modifier
                    .weight(dividerRatio)
                    .fillMaxHeight()
            ) {
                EditorTextPane(
                    content = content,
                    onContentChange = viewModel::onEditorContentChange,
                    textScroll = textScroll,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 中间：可拖动分割线
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { change, dragAmount ->
                            change.consume()
                            dividerRatio = (dividerRatio + dragAmount / rowWidth)
                                .coerceIn(0.2f, 0.8f)
                        }
                    }
            )

            // 右侧：Markdown 实时预览
            Box(
                modifier = Modifier
                    .weight(1f - dividerRatio)
                    .fillMaxHeight()
            ) {
                MarkdownWebView(
                    markdown = content,
                    themeMode = settings.themeMode,
                    modifier = Modifier.fillMaxSize(),
                    onScrollRatioChanged = { ratio ->
                        if (!syncing) {
                            syncing = true
                            previewRatio = ratio
                            syncing = false
                        }
                    },
                    scrollRatio = previewRatio
                )
            }
                }
            }
        }
    }

    // ---------- 历史版本对话框 ----------
    if (showHistory) {
        HistoryDialog(
            history = history,
            onDismiss = { showHistory = false },
            onRestore = { restored ->
                viewModel.restoreHistory(restored)
                showHistory = false
            }
        )
    }

    // ---------- 删除确认 ----------
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除笔记") },
            text = { Text("确定要删除「$title」吗？此操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        currentNoteId?.let { viewModel.deleteNote(it) }
                        showDeleteConfirm = false
                        onBack()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }

    // ---------- AI 摘要结果 ----------
    if (summaryResult != null) {
        AlertDialog(
            onDismissRequest = {
                showSummary = false
                viewModel.clearSummary()
            },
            title = { Text("AI 智能摘要") },
            text = { Text(summaryResult ?: "") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSummary = false
                        viewModel.clearSummary()
                    }
                ) { Text("关闭") }
            }
        )
    }
}

@Composable
private fun EditorToolbar(
    isPreview: Boolean,
    onToggleLayout: () -> Unit,
    onToggleWysiwyg: () -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    isSummarizing: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onImage: () -> Unit,
    onCamera: () -> Unit,
    onVoice: () -> Unit,
    onSummarize: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarButton(
                text = if (isPreview) "原文" else "预览",
                onClick = onToggleLayout
            )
            ToolbarButton(
                text = "WYSIWYG",
                onClick = onToggleWysiwyg
            )
            ToolbarButton("撤销", enabled = canUndo, onClick = onUndo)
            ToolbarButton("重做", enabled = canRedo, onClick = onRedo)
            ToolbarButton("图片", onClick = onImage)
            ToolbarButton("相机", onClick = onCamera)
            ToolbarButton("语音", onClick = onVoice)
            if (isSummarizing) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("摘要中…", style = MaterialTheme.typography.labelLarge)
                }
            } else {
                ToolbarButton("AI摘要", onClick = onSummarize)
            }
        }
    }
}

@Composable
private fun ToolbarButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 原始 Markdown 编辑区（仅原文 / 双栏共用）：文本块可编辑，Base64 图片渲染为可点击缩略图 */
@Composable
private fun EditorTextPane(
    content: String,
    onContentChange: (String) -> Unit,
    textScroll: ScrollState,
    modifier: Modifier = Modifier
) {
    val blocks = remember(content) { parseEditorBlocks(content) }
    var previewImage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .verticalScroll(textScroll)
            .padding(16.dp)
    ) {
        if (blocks.isEmpty()) {
            // 空内容：显示可输入的占位编辑框
            BasicTextField(
                value = "",
                onValueChange = onContentChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                decorationBox = { inner ->
                    Box {
                        Text(
                            text = "开始输入…",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        inner()
                    }
                }
            )
        }
        blocks.forEachIndexed { index, block ->
            when (block) {
                is EditorBlock.TextBlock -> BasicTextField(
                    value = block.text,
                    onValueChange = { newText ->
                        onBlockTextChange(blocks, index, newText, onContentChange)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
                is EditorBlock.ImageBlock -> ImageThumbnail(
                    base64 = block.base64,
                    onClick = { previewImage = block.base64 },
                    onDelete = {
                        deleteImageBlock(blocks, index, onContentChange)
                        if (previewImage == block.base64) previewImage = null
                    }
                )
            }
        }
    }

    // 点击缩略图 → 大图预览
    previewImage?.let { b64 ->
        AlertDialog(
            onDismissRequest = { previewImage = null },
            confirmButton = {
                TextButton(onClick = { previewImage = null }) { Text("关闭") }
            },
            text = {
                val bitmap = remember(b64) { decodeBase64Bitmap(b64) }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "图片预览",
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text("图片解码失败")
                }
            }
        )
    }
}

/** 内容块：纯文本 or 内嵌 Base64 图片 */
private sealed class EditorBlock {
    data class TextBlock(val text: String) : EditorBlock()
    data class ImageBlock(val base64: String) : EditorBlock()
}

private val IMAGE_RE = Regex(
    """!\[[^\]]*\]\(data:image/[a-zA-Z0-9+.-]+;base64,[A-Za-z0-9+/=]+\)"""
)

/** 解析 Markdown 文本，将 Base64 图片语法拆分为独立图片块 */
private fun parseEditorBlocks(content: String): List<EditorBlock> {
    if (content.isBlank()) return emptyList()
    val blocks = mutableListOf<EditorBlock>()
    var last = 0
    for (m in IMAGE_RE.findAll(content)) {
        if (m.range.first > last) {
            blocks.add(EditorBlock.TextBlock(content.substring(last, m.range.first)))
        }
        val body = m.value.substringAfter("base64,").substringBefore(")")
        blocks.add(EditorBlock.ImageBlock(body))
        last = m.range.last + 1
    }
    if (last < content.length) {
        blocks.add(EditorBlock.TextBlock(content.substring(last)))
    }
    return blocks
}

/** 删除某个图片块后，重组完整内容并回调（自动保存 / 撤销栈 / 历史快照全部沿用原逻辑） */
private fun deleteImageBlock(
    blocks: List<EditorBlock>,
    index: Int,
    onContentChange: (String) -> Unit
) {
    val sb = StringBuilder()
    blocks.forEachIndexed { i, b ->
        if (i != index) {
            when (b) {
                is EditorBlock.TextBlock -> sb.append(b.text)
                is EditorBlock.ImageBlock -> sb.append("![](data:image/jpeg;base64,${b.base64})")
            }
        }
    }
    onContentChange(sb.toString())
}

/** 编辑某个文本块后，重组完整内容并回调（自动保存 / 撤销栈 / 历史快照全部沿用原逻辑） */
private fun onBlockTextChange(
    blocks: List<EditorBlock>,
    index: Int,
    newText: String,
    onContentChange: (String) -> Unit
) {
    val sb = StringBuilder()
    blocks.forEachIndexed { i, b ->
        when {
            i == index -> sb.append(newText)
            b is EditorBlock.TextBlock -> sb.append(b.text)
            b is EditorBlock.ImageBlock -> sb.append("![](data:image/jpeg;base64,${b.base64})")
        }
    }
    onContentChange(sb.toString())
}

/** Base64 → Bitmap（解码失败返回 null，UI 兜底提示） */
private fun decodeBase64Bitmap(base64: String): android.graphics.Bitmap? = try {
    val bytes = Base64.decode(base64, Base64.NO_WRAP)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
} catch (e: Exception) {
    null
}

/** 图片缩略图：点击查看大图，右上角删除角标一键删除 */
@Composable
private fun ImageThumbnail(
    base64: String,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val bitmap = remember(base64) { decodeBase64Bitmap(base64) }
    if (bitmap == null) {
        Box(modifier = Modifier.padding(vertical = 8.dp)) {
            Text(
                text = "[图片解码失败]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            DeleteBadge(onDelete = onDelete)
        }
        return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "插入的图片",
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick),
            contentScale = ContentScale.Fit
        )
        DeleteBadge(onDelete = onDelete)
    }
}

/** 右上角删除角标（×） */
@Composable
private fun BoxScope.DeleteBadge(onDelete: () -> Unit) {
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(6.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.errorContainer)
            .clickable(onClick = onDelete),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "删除图片",
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(16.dp)
        )
    }
}