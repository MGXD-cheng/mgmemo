# MGMemo - 极简 Markdown 备忘录

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-green)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/license-MIT-orange)](LICENSE)

> 一款专注写作体验的 Android Markdown 备忘录应用，支持实时预览、WYSIWYG 编辑、语音输入、AI 摘要、图片内嵌、PDF/Word 导出等。

## ✨ 功能特性

| 功能 | 状态 | 说明 |
|------|------|------|
| Markdown 编辑 | ✅ | 含实时预览、双栏/单栏/WYSIWYG 多种模式 |
| 所见即所得 | ✅ | 渲染后直接编辑，Turndown.js 自动回写 MD |
| 代码高亮 | ✅ | highlight.js 支持 190+ 语言 |
| LaTeX 公式 | ✅ | KaTeX 行内/块级渲染 |
| Mermaid 图表 | ✅ | 流程图/时序图/甘特图 |
| 图片内嵌 | ✅ | Base64 图片缩略图+大图预览，可删除 |
| AI 摘要 | ✅ | OpenAI 兼容 API，一键生成摘要 |
| 语音输入 | ✅ | SpeechRecognizer + Intent 降级链（华为适配） |
| 导出 PDF | ✅ | 原生 PdfDocument 渲染 |
| 导出 Word | ✅ | DOCX（AltChunk HTML 嵌入） |
| 导出 Markdown | ✅ | 纯 .md 文件分享 |
| 打开方式 | ✅ | 支持 .md/.markdown/.txt 文件导入 |
| 撤销/重做 | ✅ | 30 步历史栈 |
| 自动保存 | ✅ | 500ms 防抖 |
| 全文搜索 | ✅ | SQLite FTS |
| 标签系统 | ✅ | 分类管理 |
| 生物识别锁 | ✅ | 指纹/面部解锁 |
| 深色模式 | ✅ | 亮色/暗色/护眼绿 |

## 🛠️ 技术栈

- **语言**: 100% Kotlin
- **UI**: Jetpack Compose + Material3
- **架构**: MVVM (AndroidViewModel + StateFlow)
- **数据库**: Room (SQLite)
- **渲染**: WebView + marked + highlight.js + KaTeX + Mermaid + Turndown
- **AI**: Retrofit + OkHttp（OpenAI 兼容接口）
- **构建**: Gradle 9.0 + AGP 9.0 + Kotlin 2.0

## 📦 构建

```bash
git clone https://github.com/MGXD-cheng/mgmemo.git
cd mgmemo
./gradlew assembleDebug
```

### 环境要求

- JDK 17+
- Android SDK 35

## 📱 安装

下载最新 APK 从 [Releases](https://github.com/MGXD-cheng/mgmemo/releases)，允许「未知来源」安装后即可使用。

## 📄 打开方式

在文件管理器中点击 `.md` / `.markdown` / `.txt` 文件 → 选择「MGMemo」打开，自动导入为笔记。

## 📝 许可证

MIT License © 2025 MGXD-cheng

---
*Made with ❤️ and Jetpack Compose*