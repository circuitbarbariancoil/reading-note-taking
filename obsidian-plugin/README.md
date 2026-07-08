# Reading Notes — Obsidian 插件

读书笔记 Android App 的桌面只读渲染端（DESIGN.md §7）。App 是唯一数据源，
单向 App → Dropbox → Obsidian，插件绝不回写 `book.json`。

## 功能

- **一书一笔记**：命令「同步书笔记」为 Dropbox 上每本书生成/维护一篇 `.md`
  （frontmatter `app_book: <uid>` 定身份，移动改名不受影响）。
- **append-only 增量**：每条 entry = 一行 `` `app:<id>` `` 锚 + 写实内容
  （原文引用块 + 💬批注 + #tag）。已写入的旧条目**永不改动**；新条目按书内
  顺序（页码+页内偏移）插到「最近后继锚行」紧前面，不打断你写的内容。
- **锚行渲染**：阅读视图里锚行变成小芯片：页码 + [📷 页图]（Dropbox 临时
  直链现拉弹窗）+ [📖 原文]（打开 A 视图定位该页）；源已删则显示 ⚠️。
- **高亮语法**：`~={颜色名}文字=~` 在阅读视图渲染成对应颜色，色值读共享的
  `/ReadingVault/config/highlight-colors.json`（App 调色盘保存时上传）。
- **A 视图（最小版）**：tab 内类 App 浏览器——书架 → 书（目录树 + 页列表）
  → 单页（页图现拉 + 带高亮的冻结原文，横排）。

## 构建

```bash
cd obsidian-plugin
npm install
npm run build   # 产出 main.js
```

把 `manifest.json`、`main.js`、`styles.css` 复制到 vault 的
`.obsidian/plugins/reading-notes/` 并在 Obsidian 里启用。

## 连接 Dropbox

设置 → Reading Notes → 连接：打开授权链接（同一个 Dropbox app），把返回的
代码粘回来即可（PKCE + refresh token，长期有效）。
