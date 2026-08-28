package com.mgmemo.app.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/**
 * 可滚动的 WebView 封装：加载内嵌 HTML 模板（marked + highlight.js + KaTeX + Mermaid CDN），
 * 通过 evaluateJavascript 实时传递 Markdown 文本渲染。
 */
class ObservableWebView(context: Context) : WebView(context) {

    var onScrollRatioChanged: ((Float) -> Unit)? = null
    var onContentChanged: ((String) -> Unit)? = null

    init {
        addJavascriptInterface(WysiwygBridge { md -> onContentChanged?.invoke(md) }, "AndroidBridge")
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val max = contentHeight - height
        val ratio = if (max > 0) t.toFloat() / max else 0f
        onScrollRatioChanged?.invoke(ratio)
    }

    private class WysiwygBridge(private val onMarkdown: (String) -> Unit) {
        @JavascriptInterface
        fun onContentChanged(markdown: String) {
            onMarkdown(markdown)
        }
    }
}

private const val HTML_TEMPLATE = """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.css">
<link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/styles/github.min.css">
<style>
:root {
  --bg: #ffffff;
  --fg: #1f2328;
  --code-bg: #f6f8fa;
  --border: #d0d7de;
  --quote: #57606a;
  --link: #0969da;
  --table-head: #f6f8fa;
  --mermaid-bg: transparent;
}
* { box-sizing: border-box; }
body {
  margin: 0;
  padding: 16px;
  background: var(--bg);
  color: var(--fg);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
  font-size: 15px;
  line-height: 1.7;
  overflow-wrap: break-word;
}
h1, h2, h3, h4 { margin: 1.2em 0 0.6em; line-height: 1.3; }
h1 { font-size: 1.8em; border-bottom: 1px solid var(--border); padding-bottom: 0.3em; }
h2 { font-size: 1.4em; border-bottom: 1px solid var(--border); padding-bottom: 0.3em; }
h3 { font-size: 1.15em; }
code {
  background: var(--code-bg);
  padding: 0.2em 0.4em;
  border-radius: 4px;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo, monospace;
  font-size: 0.9em;
}
pre { background: var(--code-bg); padding: 12px; border-radius: 6px; overflow-x: auto; margin: 1em 0; }
pre code { background: transparent; padding: 0; }
blockquote { margin: 0 0 1em; padding: 0 1em; color: var(--quote); border-left: 4px solid var(--border); }
table { border-collapse: collapse; display: block; overflow-x: auto; margin: 1em 0; }
th, td { border: 1px solid var(--border); padding: 6px 13px; }
th { background: var(--table-head); font-weight: 600; }
img { max-width: 100%; border-radius: 6px; }
a { color: var(--link); }
input[type="checkbox"] { margin-right: 6px; }
ul, ol { padding-left: 2em; }
.mermaid { text-align: center; margin: 12px 0; background: var(--mermaid-bg); }
.katex-display { overflow-x: auto; overflow-y: hidden; padding: 8px 0; }
</style>
</head>
<body>
<div id="content"></div>
<script src="https://cdn.jsdelivr.net/npm/marked@12.0.2/marked.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/katex.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/katex@0.16.11/dist/contrib/auto-render.min.js"></script>
<script src="https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@11.9.0/build/highlight.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/mermaid@10.9.1/dist/mermaid.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/turndown@7.1.3/dist/turndown.js"></script>
<script>
marked.setOptions({ gfm: true, breaks: true });
mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' });

function renderMarkdown(text) {
  var html = marked.parse(text);
  document.getElementById('content').innerHTML = html;

  // LaTeX (KaTeX)
  try {
    renderMathInElement(document.getElementById('content'), {
      delimiters: [
        { left: '$$', right: '$$', display: true },
        { left: '$', right: '$', display: false },
        { left: '\\(', right: '\\)', display: false },
        { left: '\\[', right: '\\]', display: true }
      ],
      throwOnError: false
    });
  } catch (e) {}

  // 代码高亮 (highlight.js)
  document.querySelectorAll('pre code').forEach(function (el) {
    if (!el.classList.contains('language-mermaid')) {
      try { hljs.highlightElement(el); } catch (e) {}
    }
  });

  // Mermaid 图表
  var mermaidNodes = document.querySelectorAll('pre code.language-mermaid');
  var containers = [];
  mermaidNodes.forEach(function (node) {
    var pre = node.parentElement;
    if (pre) {
      var div = document.createElement('div');
      div.className = 'mermaid';
      div.textContent = node.textContent;
      pre.parentElement.replaceChild(div, pre);
      containers.push(div);
    }
  });
  if (containers.length > 0) {
    try { mermaid.run({ nodes: containers }); } catch (e) {}
  }
}

function setTheme(mode) {
  var r = document.documentElement;
  if (mode === 'dark') {
    r.style.setProperty('--bg', '#0d1117');
    r.style.setProperty('--fg', '#e6edf3');
    r.style.setProperty('--code-bg', '#161b22');
    r.style.setProperty('--border', '#30363d');
    r.style.setProperty('--quote', '#8b949e');
    r.style.setProperty('--link', '#58a6ff');
    r.style.setProperty('--table-head', '#161b22');
    r.style.setProperty('--mermaid-bg', '#ffffff');
  } else if (mode === 'green') {
    r.style.setProperty('--bg', '#c7edcc');
    r.style.setProperty('--fg', '#17321d');
    r.style.setProperty('--code-bg', '#b3d9b8');
    r.style.setProperty('--border', '#93af98');
    r.style.setProperty('--quote', '#2c4a33');
    r.style.setProperty('--link', '#1a5c28');
    r.style.setProperty('--table-head', '#b3d9b8');
    r.style.setProperty('--mermaid-bg', '#ffffff');
  } else {
    r.style.setProperty('--bg', '#ffffff');
    r.style.setProperty('--fg', '#1f2328');
    r.style.setProperty('--code-bg', '#f6f8fa');
    r.style.setProperty('--border', '#d0d7de');
    r.style.setProperty('--quote', '#57606a');
    r.style.setProperty('--link', '#0969da');
    r.style.setProperty('--table-head', '#f6f8fa');
    r.style.setProperty('--mermaid-bg', 'transparent');
  }
}

var td = new TurndownService({ headingStyle: 'atx', codeBlockStyle: 'fenced', emDelimiter: '*' });

// WYSIWYG: enable contenteditable
function enableWysiwyg() {
  var el = document.getElementById('content');
  el.contentEditable = 'true';
  el.spellcheck = false;
  el.style.outline = '2px dashed #58a6ff';
  el.style.padding = '4px';
  el.style.borderRadius = '6px';
  el.addEventListener('input', onWysiwygInput);
}

function disableWysiwyg() {
  var el = document.getElementById('content');
  el.contentEditable = 'false';
  el.style.outline = '';
  el.style.padding = '';
  el.style.borderRadius = '';
  el.removeEventListener('input', onWysiwygInput);
}

var wysiwygTimer = null;
function onWysiwygInput() {
  clearTimeout(wysiwygTimer);
  wysiwygTimer = setTimeout(function() {
    var html = document.getElementById('content').innerHTML;
    var md = td.turndown(html);
    try { AndroidBridge.onContentChanged(md); } catch(e) {}
  }, 600);
}
</script>
</body>
</html>"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkdownWebView(
    markdown: String,
    themeMode: String,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    onContentChanged: ((String) -> Unit)? = null,
    onScrollRatioChanged: ((Float) -> Unit)? = null,
    scrollRatio: Float? = null
) {
    var webView by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ObservableWebView?>(null) }
    val currentMarkdown by rememberUpdatedState(markdown)
    val currentTheme by rememberUpdatedState(themeMode)

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
                        view?.evaluateJavascript("renderMarkdown(${jsonQuote(currentMarkdown)})") {}
                        view?.evaluateJavascript("setTheme('$currentTheme')") {}
                    }
                }
                this.onScrollRatioChanged = { ratio -> onScrollRatioChanged?.invoke(ratio) }
                this.onContentChanged = { md -> onContentChanged?.invoke(md) }
                loadDataWithBaseURL(null, HTML_TEMPLATE, "text/html", "utf-8", null)
                webView = this
            }
        },
        modifier = modifier
    )

    // 内容变化 → 重新渲染
    LaunchedEffect(markdown) {
        webView?.evaluateJavascript("renderMarkdown(${jsonQuote(markdown)})") {}
    }

    // 主题变化 → 动态切换 CSS 变量
    LaunchedEffect(themeMode) {
        webView?.evaluateJavascript("setTheme('$themeMode')") {}
    }

    // WYSIWYG 模式切换
    LaunchedEffect(editable) {
        webView?.evaluateJavascript(if (editable) "enableWysiwyg()" else "disableWysiwyg()") {}
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