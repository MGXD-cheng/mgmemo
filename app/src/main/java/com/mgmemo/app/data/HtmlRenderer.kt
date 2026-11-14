package com.mgmemo.app.data

/**
 * 预览渲染器：把 Markdown / HTML 直接转成带样式的 HTML。
 *
 * 关键设计：**渲染完全在 Kotlin 端完成**，页面本身不加载任何外部脚本，
 * 因此打开笔记立刻就能看到内容，不会因为 CDN 慢/被墙而一直白屏。
 * 代码高亮、KaTeX 公式、Mermaid 图表属于「渐进增强」：首屏之后异步加载，
 * 加载成功则自动美化，失败也不影响正文显示。
 */
object HtmlRenderer {

    /** Markdown 或 HTML → 页面正文片段 */
    fun renderBody(source: String): String {
        val blocks = try {
            DocParser.parse(source)
        } catch (e: Exception) {
            return "<pre>" + escapeHtml(source) + "</pre>"
        }
        if (blocks.isEmpty()) return ""
        val sb = StringBuilder()
        blocks.forEach { sb.append(blockToHtml(it)) }
        return sb.toString()
    }

    /** 组装完整页面（首屏内容直接内联，不依赖 JS） */
    fun document(bodyHtml: String, themeMode: String): String {
        val cls = when (themeMode) {
            "dark" -> "theme-dark"
            "green" -> "theme-green"
            else -> "theme-light"
        }
        return "<!DOCTYPE html><html class=\"$cls\"><head>" +
            "<meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
            // 全部资源来自本地 assets（file:///android_asset/web/），零网络请求
            "<link rel=\"stylesheet\" href=\"katex.min.css\">" +
            "<link rel=\"stylesheet\" id=\"hljs-light\" href=\"github.min.css\">" +
            "<link rel=\"stylesheet\" id=\"hljs-dark\" href=\"github-dark.min.css\" disabled>" +
            "<style>$CSS</style></head><body>" +
            "<div id=\"content\">$bodyHtml</div>" +
            "<script>$JS</script></body></html>"
    }

    // ==================== 块渲染 ====================

    private fun blockToHtml(b: DocBlock): String = when (b) {
        is DocBlock.Heading ->
            "<h${b.level}>${runsToHtml(b.runs)}</h${b.level}>"

        is DocBlock.Paragraph -> {
            val prefix = when {
                b.bullet -> "• "
                b.ordered != null -> "${b.ordered}. "
                else -> ""
            }
            if (prefix.isEmpty()) {
                "<p>${runsToHtml(b.runs)}</p>"
            } else {
                "<p class=\"li\">$prefix${runsToHtml(b.runs)}</p>"
            }
        }

        is DocBlock.Quote ->
            "<blockquote>${runsToHtml(b.runs)}</blockquote>"

        is DocBlock.Code -> {
            val text = escapeHtml(b.lines.joinToString("\n"))
            if (b.lang.equals("mermaid", ignoreCase = true)) {
                // 交给 Mermaid 渲染；若未加载则显示源码（可读）
                "<div class=\"mermaid\">$text</div>"
            } else {
                val cls = if (b.lang.isBlank()) "" else " class=\"language-${escapeAttr(b.lang)}\""
                "<pre><code$cls>$text</code></pre>"
            }
        }

        is DocBlock.Table -> {
            val sb = StringBuilder("<table>")
            if (b.header.isNotEmpty()) {
                sb.append("<thead><tr>")
                b.header.forEach {
                    sb.append("<th>").append(runsToHtml(DocParser.parseInline(it))).append("</th>")
                }
                sb.append("</tr></thead>")
            }
            sb.append("<tbody>")
            b.rows.forEach { row ->
                sb.append("<tr>")
                row.forEach {
                    sb.append("<td>").append(runsToHtml(DocParser.parseInline(it))).append("</td>")
                }
                sb.append("</tr>")
            }
            sb.append("</tbody></table>")
            sb.toString()
        }

        is DocBlock.Image -> {
            val src = escapeAttr(b.url)
            val alt = escapeAttr(b.alt)
            if (b.url.isBlank()) "<p class=\"img\">[图片] $alt</p>"
            else "<p class=\"img\"><img src=\"$src\" alt=\"$alt\"></p>"
        }

        DocBlock.Divider -> "<hr/>"
    }

    private fun runsToHtml(list: List<DocRun>): String {
        val sb = StringBuilder()
        list.forEach { r ->
            if (r.lineBreakBefore) sb.append("<br/>")
            if (r.text.isEmpty()) return@forEach
            var s = escapeHtml(r.text)
            if (r.code) s = "<code>$s</code>"
            if (r.strike) s = "<del>$s</del>"
            if (r.italic) s = "<em>$s</em>"
            if (r.bold) s = "<strong>$s</strong>"
            sb.append(s)
        }
        return sb.toString()
    }

    private fun escapeHtml(s: String): String = s
        .replace("&", "&" + "amp;")
        .replace("<", "&" + "lt;")
        .replace(">", "&" + "gt;")

    private fun escapeAttr(s: String): String = escapeHtml(s)
        .replace("\"", "&" + "quot;")

    // ==================== 样式 ====================

    private val CSS = """
:root{
  --bg:#ffffff;--fg:#1f2328;--code-bg:#f6f8fa;--code-fg:#24292f;
  --border:#d0d7de;--quote:#57606a;--link:#0969da;--table-head:#f6f8fa;--mermaid-bg:transparent;
}
html.theme-light{color-scheme:light;
  --bg:#ffffff;--fg:#1f2328;--code-bg:#f6f8fa;--code-fg:#24292f;
  --border:#d0d7de;--quote:#57606a;--link:#0969da;--table-head:#f6f8fa;--mermaid-bg:transparent;
}
html.theme-green{color-scheme:light;
  --bg:#c7edcc;--fg:#17321d;--code-bg:#b3d9b8;--code-fg:#17321d;
  --border:#93af98;--quote:#2c4a33;--link:#1a5c28;--table-head:#b3d9b8;--mermaid-bg:#ffffff;
}
html.theme-dark{color-scheme:dark;
  --bg:#0d1117;--fg:#e6edf3;--code-bg:#1c2128;--code-fg:#e6edf3;
  --border:#3d444d;--quote:#a7b1bd;--link:#6cb6ff;--table-head:#1c2128;--mermaid-bg:#ffffff;
}
*{box-sizing:border-box;}
body{
  margin:0;padding:16px;background:var(--bg);color:var(--fg);
  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,"PingFang SC","Microsoft YaHei",sans-serif;
  font-size:15px;line-height:1.7;overflow-wrap:break-word;
  -webkit-text-size-adjust:100%;
}
h1,h2,h3,h4,h5,h6{margin:1.1em 0 .55em;line-height:1.3;color:var(--fg);}
h1{font-size:1.8em;border-bottom:1px solid var(--border);padding-bottom:.3em;}
h2{font-size:1.4em;border-bottom:1px solid var(--border);padding-bottom:.3em;}
h3{font-size:1.16em;}
p{margin:.7em 0;}
p.li{margin:.25em 0;padding-left:.2em;}
strong,b{font-weight:700;}
em,i{font-style:italic;}
del{color:var(--quote);}
code{background:var(--code-bg);color:var(--code-fg);padding:.15em .4em;border-radius:4px;
  font-family:"SFMono-Regular",Consolas,"Liberation Mono",Menlo,monospace;font-size:.9em;}
pre{background:var(--code-bg);color:var(--code-fg);padding:12px;border-radius:6px;overflow-x:auto;margin:.9em 0;}
pre code{background:transparent;padding:0;color:var(--code-fg);}
/* highlight.js 自带背景色会与主题冲突，这里统一交给 pre */
pre code.hljs{background:transparent !important;padding:0 !important;}
blockquote{margin:.8em 0;padding:0 1em;color:var(--quote);border-left:4px solid var(--border);}
table{border-collapse:collapse;display:block;overflow-x:auto;margin:.9em 0;max-width:100%;}
th,td{border:1px solid var(--border);padding:6px 12px;color:var(--fg);}
th{background:var(--table-head);font-weight:700;}
img{max-width:100%;height:auto;border-radius:6px;}
p.img{margin:.8em 0;text-align:center;}
a{color:var(--link);}
hr{border:none;border-top:1px solid var(--border);margin:1.2em 0;}
ul,ol{padding-left:1.6em;}
li{color:var(--fg);}
input[type=checkbox]{margin-right:6px;accent-color:var(--link);}
.mermaid{background:var(--mermaid-bg);text-align:center;margin:.9em 0;white-space:pre-wrap;
  font-family:Consolas,monospace;font-size:.88em;color:var(--quote);}
.katex{color:inherit;}
.katex-display{overflow-x:auto;overflow-y:hidden;padding:6px 0;}
""".trimIndent()

    // ==================== 脚本（无外部依赖，增强按需异步加载） ====================

    private val JS = """
(function(){
  // 全部为本地 assets 资源，不依赖任何 CDN
  var URL_HLJS='highlight.min.js';
  var URL_KATEX='katex.min.js';
  var URL_KATEX_EXT='auto-render.min.js';
  var URL_MERMAID='mermaid.min.js';
  var loaded={};

  function loadScript(key,src,cb){
    if(loaded[key]){ return; }
    loaded[key]=true;
    try{
      var s=document.createElement('script');
      s.src=src; s.async=true;
      if(cb){ s.onload=function(){ try{cb();}catch(e){} }; }
      document.body.appendChild(s);
    }catch(e){}
  }
  function loadCss(href){
    try{
      var l=document.createElement('link');
      l.rel='stylesheet'; l.href=href;
      document.head.appendChild(l);
    }catch(e){}
  }

  // ---------- 主题 ----------
  window.setTheme=function(mode){
    var c=(mode==='dark')?'theme-dark':(mode==='green'?'theme-green':'theme-light');
    document.documentElement.className=c;
    try{
      var light=document.getElementById('hljs-light');
      var dark=document.getElementById('hljs-dark');
      if(light){ light.disabled=(mode==='dark'); }
      if(dark){ dark.disabled=(mode!=='dark'); }
    }catch(e){}
  };

  // ---------- 内容更新（只替换正文，不重载页面） ----------
  window.setHtml=function(html){
    // 编辑中不覆盖 innerHTML，否则会丢光标（内容本来就来自编辑区）
    if(wysiwygActive){ return; }
    var el=document.getElementById('content');
    if(!el){ return; }
    el.innerHTML=html;
    scheduleEnhance();
  };

  // ---------- 渐进增强（全部失败也不影响正文） ----------
  var enhanceTimer=null;
  function scheduleEnhance(){
    clearTimeout(enhanceTimer);
    enhanceTimer=setTimeout(enhance,450);
  }
  function enhance(){
    tryHljs(); tryMath(); tryMermaid();
  }
  function applyHljs(){
    if(!window.hljs){ return; }
    try{
      var nodes=document.querySelectorAll('pre code');
      for(var i=0;i<nodes.length;i++){
        var el=nodes[i];
        if(el.getAttribute('data-hl')==='1'){ continue; }
        try{ hljs.highlightElement(el); el.setAttribute('data-hl','1'); }catch(e){}
      }
    }catch(e){}
  }
  function tryHljs(){
    if(window.hljs){ applyHljs(); return; }
    loadScript('hljs',URL_HLJS,applyHljs);
  }
  function applyMath(){
    if(!window.renderMathInElement){ return; }
    var el=document.getElementById('content');
    if(!el){ return; }
    try{
      renderMathInElement(el,{
        delimiters:[
          {left:'${'$'}${'$'}',right:'${'$'}${'$'}',display:true},
          {left:'${'$'}',right:'${'$'}',display:false},
          {left:'\\(',right:'\\)',display:false},
          {left:'\\[',right:'\\]',display:true}
        ],
        throwOnError:false
      });
    }catch(e){}
  }
  function tryMath(){
    if(window.renderMathInElement){ applyMath(); return; }
    if(!window.katex){
      if(!loaded.katexCss){ loaded.katexCss=true; loadCss(URL_KATEX_CSS); }
      loadScript('katex',URL_KATEX,function(){ loadScript('katexExt',URL_KATEX_EXT,applyMath); });
      return;
    }
    loadScript('katexExt',URL_KATEX_EXT,applyMath);
  }
  function applyMermaid(){
    if(!window.mermaid){ return; }
    try{
      var nodes=document.querySelectorAll('.mermaid');
      if(!nodes.length){ return; }
      mermaid.initialize({startOnLoad:false,theme:'default',securityLevel:'loose'});
      mermaid.run({nodes:nodes});
    }catch(e){}
  }
  function tryMermaid(){
    if(window.mermaid){ return; }
    if(!document.querySelector('.mermaid')){ return; }
    loadScript('mermaid',URL_MERMAID,applyMermaid);
  }

  // ---------- 所见即所得 ----------
  var wysiwygActive=false, editTimer=null;
  window.enableWysiwyg=function(){
    var el=document.getElementById('content');
    if(!el){ return; }
    wysiwygActive=true;
    el.contentEditable='true';
    el.spellcheck=false;
    el.style.outline='2px dashed #58a6ff';
    el.style.outlineOffset='4px';
    el.style.padding='8px';
    el.style.borderRadius='6px';
    el.style.minHeight='60vh';
    el.removeEventListener('input',onEdit);
    el.addEventListener('input',onEdit);
    el.removeEventListener('blur',flushEdit);
    el.addEventListener('blur',flushEdit);
  };
  window.disableWysiwyg=function(){
    var el=document.getElementById('content');
    if(!el){ return; }
    flushEdit();
    wysiwygActive=false;
    el.contentEditable='false';
    el.style.outline=''; el.style.outlineOffset=''; el.style.padding='';
    el.style.borderRadius=''; el.style.minHeight='';
    el.removeEventListener('input',onEdit);
    el.removeEventListener('blur',flushEdit);
  };
  function onEdit(){
    clearTimeout(editTimer);
    editTimer=setTimeout(flushEdit,400);
  }
  function flushEdit(){
    if(!wysiwygActive){ return; }
    var el=document.getElementById('content');
    if(!el){ return; }
    try{ AndroidBridge.onHtmlChanged(el.innerHTML); }catch(e){}
  }

  // 首屏之后再做增强，绝不阻塞显示
  setTimeout(enhance,300);
})();
""".trimIndent()
}