package com.mgmemo.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.mgmemo.app.ui.screens.NoteEditorScreen
import com.mgmemo.app.ui.screens.NoteListScreen
import com.mgmemo.app.ui.screens.SettingsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mgmemo.app.ui.theme.MGMemoTheme
import com.mgmemo.app.viewmodel.NotesViewModel

class MainActivity : FragmentActivity() {

    private lateinit var viewModel: NotesViewModel
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private var lastAuthTime = 0L
    private var locked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[NotesViewModel::class.java]

        biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    lastAuthTime = System.currentTimeMillis()
                    locked = false
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
                        errorCode == BiometricPrompt.ERROR_CANCELED
                    ) {
                        // 用户取消：保持锁定，可重试
                        return
                    }
                    // 设备级错误：避免卡死，解除锁定
                    locked = false
                }
            }
        )
        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("MGMemo 已锁定")
            .setSubtitle("验证身份以继续使用")
            .setNegativeButtonText("取消")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        // 小组件点击进入
        if (intent.getLongExtra("note_id", -1) > 0) {
            viewModel.openNote(intent.getLongExtra("note_id", -1))
        } else if (intent.getBooleanExtra("new_note", false)) {
            viewModel.createNote()
        }
        handleImportIntent(intent)

        setContent {
            val settings by viewModel.settings.collectAsState()
            MGMemoTheme(themeMode = settings.themeMode) {
                MGMemoAppContent(
                    viewModel = viewModel,
                    locked = locked,
                    onUnlock = { showBiometricPrompt() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val s = viewModel.settings.value
        if (s.enableBiometric && hasBiometricEnrolled()) {
            val now = System.currentTimeMillis()
            if (now - lastAuthTime > 5 * 60 * 1000L) {
                locked = true
                showBiometricPrompt()
            }
        }
    }

    private fun hasBiometricEnrolled(): Boolean {
        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!km.isDeviceSecure) return false
        val bm = BiometricManager.from(this)
        return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun showBiometricPrompt() {
        try {
            if (!isFinishing && !isDestroyed) {
                biometricPrompt.authenticate(promptInfo)
            }
        } catch (e: Exception) {
            locked = false
        }
    }

    /** 处理外部文件打开 Intent → 导入内容并创建笔记 */
    private fun handleImportIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SEND) return
        val uri = intent.data ?: intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val text = contentResolver.openInputStream(uri)?.use { stream ->
                    BufferedReader(InputStreamReader(stream, "UTF-8")).readText()
                } ?: return@launch
                withContext(Dispatchers.Main) {
                    viewModel.createNoteImported(text)
                }
            } catch (e: Exception) {
                Log.e("MGMemo", "导入文件失败", e)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleImportIntent(intent)
    }
}

@Composable
private fun MGMemoAppContent(
    viewModel: NotesViewModel,
    locked: Boolean,
    onUnlock: () -> Unit
) {
    var showSettings by remember { mutableStateOf(false) }
    val currentNoteId by viewModel.currentNoteId.collectAsState()

    // 打开笔记/新建笔记 → 自动进入编辑页；关闭编辑页 → 自动回列表
    LaunchedEffect(currentNoteId) {
        if (currentNoteId == null) {
            showSettings = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (locked) Modifier.blur(16.dp) else Modifier)
    ) {
        when {
            showSettings -> SettingsScreen(
                viewModel = viewModel,
                onBack = { showSettings = false }
            )
            currentNoteId != null -> NoteEditorScreen(
                viewModel = viewModel,
                onBack = { viewModel.closeEditor() }
            )
            else -> NoteListScreen(
                viewModel = viewModel,
                onOpenNote = { viewModel.openNote(it) },
                onOpenSettings = { showSettings = true },
                onNewNote = { viewModel.createNote() }
            )
        }
    }

    // 生物识别锁遮罩
    if (locked) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.65f)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = Color.White
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "MGMemo 已锁定",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "验证身份以继续使用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f)
                )
                TextButton(onClick = onUnlock) {
                    Text("立即验证", color = Color.White)
                }
            }
        }
    }
}