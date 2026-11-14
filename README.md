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
| AI 智能摘要 | ✅ | 生成一次即与笔记关联保存，再次点击直接展示 |
| AI 起标题 | ✅ | 一键根据正文拟标题，也可手动编辑（标题不会被自动覆盖） |
| AI 一键分类 | ✅ | 读取全部笔记，按主题自动归类并写回标签 |
| AI 推荐标签 | ✅ | 结合全部笔记类型推荐候选标签，点选即用 |
| 代码高亮 | ✅ | highlight.js 支持 190+ 语言 |
| LaTeX 公式 | ✅ | KaTeX 行内/块级渲染 |
| Mermaid 图表 | ✅ | 流程图/时序图/甘特图 |
| 图片内嵌 | ✅ | Base64 图片缩略图+大图预览，可删除 |
| 语音输入 | ✅ | SpeechRecognizer + Intent 降级链（华为适配） |
| 导出 PDF | ✅ | 原生 Canvas 渲染（分级字号/加粗/表格/图片/分页），默认存到「下载/MGMemo」 |
| 导出 Word | ✅ | 真正的 WordprocessingML（Word / WPS / Google Docs 均可正常打开） |
| 导出 Markdown | ✅ | 纯 .md 文件分享，默认存到「下载/MGMemo」 |
| 原生 HTML | ✅ | 笔记里的 HTML 会被结构化解析并渲染（不是显示源码） |
| 分享纯文本 | ✅ | 剥离全部 Markdown/HTML 标记，输出可读文本 |
| 打开方式 | ✅ | 支持 .md/.markdown/.txt 文件导入 |
| 标签管理 | ✅ | 统一入口：新建 / 重命名 / 删除 / 绑定笔记 |
| 备份与恢复 | ✅ | 导出 / 导入 JSON 备份（笔记 + 历史版本，**不含 API Key**），支持合并或覆盖导入 |
| 撤销/重做 | ✅ | 30 步历史栈 |
| 自动保存 | ✅ | 500ms 防抖 |
| 生物识别锁 | ✅ | 指纹/面部解锁 |
| 深色模式 | ✅ | 亮色/暗色/护眼绿 |
| 应用图标 | ✅ | 绿色主题自适应图标 + 单色主题图标 + 全密度 PNG |

### AI 能力的边界（安全设计）

- **AI 起标题**：生成结果先填入弹窗，确认后才落库；手动标题不会被正文自动覆盖
- **AI 分类**：只「追加」标签，**绝不删除**你已有的标签；改动前弹窗逐条确认（可勾选）
- **AI 摘要**：生成一次即随笔记保存，后续点击直接展示，可手动「重新生成」
- **AI 推荐标签**：结合全部笔记标题给出候选，点选即用，不自动写入

## 🛠️ 技术栈

- **语言**: 100% Kotlin
- **UI**: Jetpack Compose + Material3
- **架构**: MVVM (AndroidViewModel + StateFlow)
- **数据库**: Room (SQLite)
- **预览渲染**: Kotlin 端渲染（HtmlRenderer）+ WebView 仅负责显示；高亮/公式/图表为异步渐进增强
- **AI**: Retrofit + OkHttp（OpenAI 兼容接口）
- **构建**: Gradle 9.0 + AGP 9.0 + Kotlin 2.0

## 🖼️ 预览渲染（不依赖 CDN）

Markdown → HTML 全部在 Kotlin 端完成，WebView 页面里**没有任何阻塞式外部脚本**：

- **首屏正文直接内联进 HTML** → 打开笔记立刻可见，不会再因 CDN 超时而一直白屏
- 内容更新走 `setHtml()`，只替换正文片段，不重载页面（保留滚动位置、无闪烁）
- 代码高亮 / KaTeX 公式 / Mermaid 图表在首屏之后**异步加载**：加载成功则美化，失败也不影响正文（图表会退化为可读源码）
- 所见即所得的 HTML → Markdown 由 `MarkdownWriter` 纯 Kotlin 实现（不再依赖 Turndown）
- 图片按需解码（`Image.bytes()`），避免每次渲染都解 Base64 造成卡顿

## 📤 导出实现（不依赖 WebView）

导出走一条**确定性管线**，因此不会出现空白文档：

```
笔记内容 ──► DocParser ──► List<DocBlock> ──┬──► PdfWriter  ──► PDF（原生 Canvas 绘制）
（Markdown / 原生 HTML）                     ├──► WordWriter ──► DOCX（WordprocessingML）
                                            └──► 纯文本     ──► 分享用文本
```

- `DocModel.kt` — 统一文档模型 + Markdown / HTML 解析（行内样式、表格、列表、图片）
- `PdfWriter.kt` — Canvas 绘制：分级标题、加粗/斜体、列表、引用、代码块底色、表格线框、图片、自动分页与页码
- `WordWriter.kt` — 生成标准 OOXML：`w:p` / `w:r` / `w:b` / `w:sz` / `w:tbl` / `w:drawing`（图片内嵌 media）
- `ExportUtils.kt` — 纯文本剥离 + 写入系统「下载/MGMemo」（Android 10+ 走 MediaStore，无需权限）

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