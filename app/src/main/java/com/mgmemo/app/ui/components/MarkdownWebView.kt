package com.mgmemo.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.mgmemo.app.data.HtmlRenderer
import com.mgmemo.app.data.MarkdownWriter
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Markdown 预览容器。
 *
 * 渲染由 Kotlin 端（[HtmlRenderer]）完成，WebView 只负责显示：
 * 首屏正文直接内联进 HTML，**不加载任何阻塞式外部脚本**，
 * 所以打开笔记立刻可见，不会再因为 CDN 超时而一直白屏。
 */
class ObservableWebView(context: Context) : WebView(context) {

    var onScrollRatioChanged: ((Float) -> Unit)? = null

    /** WYSIWYG 回传 HTML 后，已转换为 Markdown 再回调 */
    var onMarkdownChanged: ((String) -> Unit)? = null

    init {
        addJavascriptInterface(
            Bridge { html ->
                try {
                    val md = MarkdownWriter.fromHtml(html)
                    if (md.isNotBlank()) onMarkdownChanged?.invoke(md)
                } catch (e: Exception) {
                    // 单次转换失败不影响继续编辑
                }
            },
            "AndroidBridge"
        )
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val max = contentHeight - height
        val ratio = if (max > 0) t.toFloat() / max else 0f
        onScrollRatioChanged?.invoke(ratio)
    }

    private class Bridge(private val onHtml: (String) -> Unit) {
        @JavascriptInterface
        fun onHtmlChanged(html: String) {
            onHtml(html)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownWebView(
    /** 已渲染好的正文 HTML 片段（由 HtmlRenderer 生成） */
    html: String,
    themeMode: String,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    onContentChanged: ((String) -> Unit)? = null,
    onScrollRatioChanged: ((Float) -> Unit)? = null,
    scrollRatio: Float? = null
) {
    var webView by remember { mutableStateOf<ObservableWebView?>(null) }
    val currentEditable by rememberUpdatedState(editable)

    // 首屏文档（含正文）只在创建时生成一次，避免重载闪烁
    val initialDocument = remember { HtmlRenderer.document(html, themeMode) }

    AndroidView(
        factory = { ctx ->
            ObservableWebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // 页面就绪极快（无外部脚本），若需编辑则立即进入编辑态
                        if (currentEditable) {
                            view?.evaluateJavascript("enableWysiwyg()", null)
                        }
                    }
                }
                this.onScrollRatioChanged = { ratio -> onScrollRatioChanged?.invoke(ratio) }
                this.onMarkdownChanged = { md -> onContentChanged?.invoke(md) }
                // baseURL 指向本地 assets，页面里的 css/js/fonts 全部从 APK 内读取（零网络）
                loadDataWithBaseURL(
                    "file:///android_asset/web/",
                    initialDocument,
                    "text/html",
                    "utf-8",
                    null
                )
                webView = this
            }
        },
        modifier = modifier
    )

    // 内容变化 → 只替换正文，不重载页面（合并高频输入）
    LaunchedEffect(html) {
        val w = webView ?: return@LaunchedEffect
        delay(120)
        w.evaluateJavascript("setHtml(${jsonQuote(html)})", null)
    }

    // 主题变化 → 只切换 html 上的 class
    LaunchedEffect(themeMode) {
        webView?.evaluateJavascript("setTheme('$themeMode')", null)
    }

    // 所见即所得开关
    LaunchedEffect(editable, webView) {
        val w = webView ?: return@LaunchedEffect
        if (editable) {
            w.evaluateJavascript("enableWysiwyg()", null)
        } else {
            w.evaluateJavascript("disableWysiwyg()", null)
            // 退出编辑后强制按最新内容重渲染，避免残留编辑态视图
            w.evaluateJavascript("setHtml(${jsonQuote(html)})", null)
        }
    }

    // 外部滚动驱动（编辑区 → 预览区）
    LaunchedEffect(scrollRatio) {
        val ratio = scrollRatio ?: return@LaunchedEffect
        val w = webView ?: return@LaunchedEffect
        val max = w.contentHeight - w.height
        if (max > 0 && ratio in 0f..1f) {
            w.scrollTo(0, (max * ratio).toInt())
        }
    }
}

/** 将字符串转成 JS 安全引号字符串 */
private fun jsonQuote(s: String): String = JSONObject.quote(s)