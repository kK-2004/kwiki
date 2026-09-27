# kwiki 前端视觉重塑设计（用户端 + 管理端）

日期：2026-09-27
参考：`docs/Design-md/mintlify`（主）、`docs/Design-md/notion`（阅读温度）、`docs/Design-md/linear`（仅借鉴克制的强调色用法）

## 1. 目标与范围

- 目标：以 Mintlify 为主基调、融入 Notion 的暖意，统一 `frontend/`（用户端）与 `admin-frontend/`（管理端）的视觉语言。
- 改动深度：**视觉 + 布局微调**。允许调整页面内部排布，不改变信息架构、路由、业务逻辑与接口调用。
- 主题：两个前端都支持**白天 / 黑夜 / 跟随系统**三种模式切换（见第 6 节）。
- 不做：组件库迁移、除主题切换外的新增功能。

## 2. 实现路径

令牌优先 + 逐组件替换：

1. 用户端重建 `frontend/src/styles/tokens.css` 为完整设计系统；管理端新增 `admin-frontend/src/styles/tokens.css`，令牌值与用户端一致。
2. 逐个组件把 scoped 样式里的硬编码颜色/圆角/阴影替换为令牌，并按第 4、5 节做布局微调。
3. 组件库主题对齐：用户端 Naive UI 通过 `themeOverrides`、Element Plus 通过 `--el-*` CSS 变量；管理端 Element Plus 通过 `--el-*` CSS 变量。
4. 保留旧令牌名（`--kwiki-green`、`--kwiki-line` 等）作为别名指向新令牌，保证未改到的组件不走样。

## 3. 设计令牌

| 类别 | 令牌 | 值 |
|---|---|---|
| 画布 | `--k-canvas` | `#ffffff` |
| 次级面（侧边栏等） | `--k-surface` | `#fafaf9` |
| 悬停面 | `--k-surface-hover` | `#f3f3f1` |
| 细线 | `--k-line` / `--k-line-strong` | `#e8e8e6` / `#dcdcd9` |
| 文字 | `--k-ink` / `--k-ink-2` / `--k-muted` / `--k-faint` | `#0a0a0a` / `#3a3a3c` / `#6b6b6e` / `#a8a8aa` |
| 主按钮 | `--k-primary` / `--k-primary-hover` | `#0a0a0a` / `#2a2a2c` |
| 品牌绿 | `--k-green` / `--k-green-deep` / `--k-green-soft` | `#10b981` / `#047857` / `#ecfdf5` |
| 危险 | `--k-danger` / `--k-danger-soft` | `#c43d3d` / `#fdf2f2` |
| 警告 | `--k-warn` | `#9a5f06` |
| 代码面 | `--k-code-bg` | `#f7f7f5`（深色见第 6 节） |
| 圆角 | `--k-r-sm` / `--k-r` / `--k-r-lg` / `--k-r-pill` | 6 / 8 / 12 / 999px |
| 阴影 | `--k-shadow-sm` / `--k-shadow` / `--k-shadow-float` | `0 1px 2px rgba(0,0,0,.04)` / `0 4px 12px rgba(0,0,0,.06)` / `0 12px 32px rgba(0,0,0,.10)` |
| 焦点环 | `--k-focus-ring` | `0 0 0 3px rgba(16,185,129,.18)` |
| 动效 | `--k-ease` | `150ms cubic-bezier(.2,0,0,1)` |

浅色主题下 `--k-green-deep`、`--k-danger`、`--k-warn` 已由 `#059669` / `#d45656` / `#c37d0d` 加深，使其在白底与对应浅底上均满足 WCAG AA 正文 4.5:1 对比度。

绿色**只**用于：选中态、焦点环、链接、品牌标识、成功提示。主操作按钮一律黑色。

字体：`Inter, "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", system-ui, sans-serif`；代码 `"Geist Mono", ui-monospace, SFMono-Regular, Menlo, monospace`。

字号层级：页面标题 28/1.2/600/−0.5px；区块标题 18/1.4/600/−0.2px；卡片标题 16/1.4/600/−0.2px；正文 14/1.6；辅助 13/1.5；说明 12/1.4；区块标签 11/600/字距 .6px 大写感。

## 4. 用户端布局微调

- **通用控件**（`tokens.css` 内的 `.ui-button` / `.ui-input` / `.ui-icon` / `.ui-error` / `.ui-notice`）：`.ui-button.primary` 黑底白字；普通按钮白底细线；输入框细线 + 绿色焦点环；高度 36px。
- **全局侧边栏**（`WorkspaceShell.vue`）：暖灰底；“新聊天”黑色主按钮；导航选中项白底 + 细线 + 绿色图标；最近访问更紧凑；账号区细线分隔。
- **知识库列表**（`KnowledgeBasePage.vue`）：`grid-template-columns: repeat(auto-fill, minmax(280px, 1fr))`；卡片细线边框，悬停边框加深 + 上浮 1px；底部操作悬停显现（键盘聚焦时同样显现）。
- **Wiki 工作区**（`WikiWorkspaceLayout.vue`、`WikiTree.vue`、`WorkspacePage.vue`、`PageReader.vue`、`PageEditor.vue`）：目录树选中项左侧 2px 绿色竖线；阅读区最大宽度约 760px；Markdown 排版采用 Notion 阅读节奏（标题上下间距、引用左边线、深色代码块、细线表格）。
- **聊天**（`ConversationPage.vue`、`ConversationMessages.vue`、`ConversationLauncher.vue`、`AgenticAnswerPanel.vue` 等）：欢迎区居中；建议卡片细线；输入框圆角 12 + 悬浮阴影；“问问 kwiki”悬浮按钮黑色胶囊。
- **登录/注册**（`AuthPage.vue`）：白卡片，背景为柔和天空渐变（`#eaf2f8` → `#faf6ef`）。
- **其余页面**（回收站、消息中心、个人空间、设置、邀请、转让、各类对话框与抽屉）：统一到上述按钮、输入框、表格、空状态样式。

## 5. 管理端布局微调

- `App.vue` / 布局：深色顶栏改为**浅色左侧边栏**（品牌 + “索引管理”“知识图谱”两个入口 + 底部账号/退出），右侧为细页头 + 内容区。
- 指标卡：大号数字 + 标签，白底细线，无填充色。
- Element Plus 主题：`--el-color-primary` 系列指向品牌绿，`--el-border-radius-base` 8px，`--el-border-color` 为细线色，字体与用户端一致；主按钮（`type="primary"`）覆盖为黑色；表格表头 `#fafaf9`。
- 登录页与用户端登录风格一致。

## 6. 深色主题与切换

### 6.1 机制

- 模式取值：`light` / `dark` / `system`，默认 `system`；持久化到 `localStorage`（用户端键 `kwiki-theme`，管理端键 `kwiki-admin-theme`）。
- 生效方式：在 `<html>` 上设置 `data-theme="light|dark"`，并同步切换 `class="dark"`（供 Element Plus 深色变量使用）。`system` 模式监听 `matchMedia('(prefers-color-scheme: dark)')` 的变化实时跟随。
- 防闪烁：在两个前端的 `index.html` `<head>` 中内联一段极短脚本，首屏渲染前读取偏好并写入 `data-theme`。
- 状态封装：各前端新增一个 `useTheme()` 组合式函数（模块级单例 `ref`），对外暴露 `mode`、`resolved`（实际生效的 light/dark）与 `setMode()`；不引入 Pinia 依赖，便于单测。
- 组件库：用户端 Naive UI 用 `NConfigProvider` 在 `resolved === 'dark'` 时传 `darkTheme`，并各自传入令牌对应的 `themeOverrides`；Element Plus 引入 `element-plus/theme-chalk/dark/css-vars.css`，再用 `--el-*` 覆盖为我们的深色令牌。

### 6.2 深色令牌（`[data-theme="dark"]` 下覆盖第 3 节同名令牌）

参考 Linear 的深色层级，但强调色保持品牌绿。

| 令牌 | 深色值 |
|---|---|
| `--k-canvas` | `#0b0b0c` |
| `--k-surface` | `#111113` |
| `--k-surface-hover` | `#1a1a1d` |
| `--k-line` / `--k-line-strong` | `#232327` / `#34343a` |
| `--k-ink` / `--k-ink-2` / `--k-muted` / `--k-faint` | `#f5f5f4` / `#d4d4d2` / `#8a8a8f` / `#5c5c61` |
| `--k-primary` / `--k-primary-hover`（主按钮反转为白底黑字） | `#f5f5f4` / `#ffffff`，文字 `--k-on-primary: #0a0a0a` |
| `--k-green` / `--k-green-deep` / `--k-green-soft` | `#34d399` / `#10b981` / `rgba(52,211,153,.12)` |
| `--k-danger` / `--k-danger-soft` | `#f07171` / `rgba(240,113,113,.12)` |
| `--k-code-bg` | `#161618` |
| 阴影 | 以细线为主，`--k-shadow-float: 0 12px 32px rgba(0,0,0,.5)` |

浅色主题新增 `--k-on-primary: #ffffff`，主按钮文字统一使用该令牌。

### 6.3 切换入口

- 用户端：全局侧边栏底部账号区旁放一个主题切换按钮（点击弹出 白天 / 黑夜 / 跟随系统 三选一菜单，当前项打勾）；侧边栏收起时只显示图标。个人空间页同时提供同样的三段选择。
- 管理端：左侧边栏底部放同样的三选一分段控件。
- 图标：`sun` / `moon` / `monitor`（lucide），并提供 aria-label。

### 6.4 特殊内容处理

- 代码高亮：按主题切换。白天代码块为浅底（`--k-code-bg: #f7f7f5`），沿用 highlight.js `github` 浅色高亮；黑夜代码块为深底（`#161618`），以 `[data-theme='dark']` 作用域覆盖为 `github-dark` 调色板。行内代码同样随主题切换浅/深底。
- PDF / DOCX 预览：页面本身保持白纸，外围画布随主题变暗。
- 用户上传的图片、视频不做反色处理。
- 附件卡片、引用块、表格、提示条全部改用令牌，不保留硬编码浅色。

## 7. 约束

- 不改业务逻辑、接口、路由、store。
- 保留 `data-testid`、aria 标签及测试依赖的类名；如有 e2e/单测选择器受影响则同步修正。
- 注释一律简体中文（见 `AGENT.md`）。
- 不触碰 `src/main/resources/db/migration/`。

## 8. 验收

- 两个前端分别通过 `typecheck`、`test`、`build`。
- 浏览器截图对比：登录、知识库列表、Wiki 工作区（阅读/编辑）、聊天、回收站、个人空间；管理端登录、索引管理、知识图谱。
- 以上页面在白天、黑夜两种主题下各截一张；切换“跟随系统”后修改系统外观能实时生效；刷新页面无闪白。
- 桌面 1440 宽与移动 375 宽均无错位。
- `useTheme()` 有单测覆盖：默认 system、持久化、system 下跟随 matchMedia 变化。
