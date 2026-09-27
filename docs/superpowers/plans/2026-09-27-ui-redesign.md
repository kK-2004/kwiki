# kwiki 前端视觉重塑 + 深浅色主题 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-09-27-ui-redesign-design.md`，把用户端与管理端重塑为 Mintlify 为主、Notion 温度的视觉语言，并支持白天 / 黑夜 / 跟随系统三种主题。

**Architecture:** 以 CSS 自定义属性（`--k-*` 令牌）为唯一视觉来源，`<html data-theme>` 切换浅/深两套令牌值；每个前端一个模块级单例 `useTheme()` 管理模式与持久化；组件 scoped 样式里的硬编码颜色全部替换为令牌，再做少量布局微调。管理端抽出带侧边栏的 `AdminLayout`。

**Tech Stack:** Vue 3.5、Vite、UnoCSS（lucide 图标）、Naive UI（仅 `NModal`）、Element Plus（用户端 PDF 工具条、管理端全部）、Vitest + @testing-library/vue、Playwright。

## Global Constraints

- 注释一律简体中文（`AGENT.md` 第 1 节）；技术标识符保留英文。
- 不改业务逻辑、接口、路由、store；保留全部 `data-testid`、aria 标签与测试依赖的类名。
- 不触碰 `src/main/resources/db/migration/`。
- 提交信息用中文 `type: 描述` 风格，**不加任何 Co-Authored-By / Claude 署名**。
- 绿色只用于：选中态、焦点环、链接、品牌标识、成功提示；主按钮浅色黑底白字、深色白底黑字。
- 主题存储键：用户端 `kwiki-theme`，管理端 `kwiki-admin-theme`；取值 `light` / `dark` / `system`，默认 `system`。
- 用户端测试基线（改动前即存在，**不属于本计划**）：`archive-navigation`、`citation-focus`、`offline-workspace` 因 vitest 无法加载 element-plus `.css` 报错，`editor.spec.ts` 中「shows failed media with delete action」断言失败。验收标准是**不新增失败**。管理端基线 3/3 通过。
- 每个任务结束都要跑：
  - 用户端：`cd frontend && npx vue-tsc --noEmit && npx vitest run`
  - 管理端：`cd admin-frontend && npx vue-tsc --noEmit && npx vitest run`

---

## 文件结构

| 文件 | 职责 | 动作 |
|---|---|---|
| `frontend/src/styles/tokens.css` | 浅/深令牌、旧令牌别名、全局基础样式、通用控件 `.ui-*` | 重写 |
| `frontend/src/styles/markdown.css` | Markdown 阅读排版 + 附件卡片（从 tokens.css 拆出） | 新建 |
| `frontend/src/styles/hljs-dark.css` | 深色下 highlight.js 配色（`[data-theme="dark"]` 作用域） | 新建 |
| `frontend/src/theme/useTheme.ts` | 主题模式单例、持久化、跟随系统 | 新建 |
| `frontend/src/theme/ThemeSwitcher.vue` | 主题切换（`menu` / `segmented` 两种形态） | 新建 |
| `frontend/index.html` | 首屏防闪烁内联脚本 | 修改 |
| `frontend/src/main.ts` | 引入新样式与 Element Plus 深色变量 | 修改 |
| `frontend/src/App.vue` | `NConfigProvider` 深色主题 | 修改 |
| `frontend/tests/theme.spec.ts` | `useTheme` 单测 | 新建 |
| `frontend/tests/theme-switcher.spec.ts` | `ThemeSwitcher` 单测 | 新建 |
| `frontend/src/features/**/*.vue` | scoped 样式换令牌 + 布局微调 | 修改 |
| `admin-frontend/src/styles/tokens.css` | 管理端令牌（与用户端同值）+ Element Plus 变量映射 | 新建 |
| `admin-frontend/src/styles.css` | 管理端页面样式改用令牌 | 重写 |
| `admin-frontend/src/theme/useTheme.ts` | 同用户端，存储键不同 | 新建 |
| `admin-frontend/src/theme/ThemeSwitcher.vue` | 分段控件形态 | 新建 |
| `admin-frontend/src/components/AdminLayout.vue` | 左侧边栏 + 页头布局 | 新建 |
| `admin-frontend/src/App.vue` | 已登录路由套 `AdminLayout` | 修改 |
| `admin-frontend/src/views/*.vue` | 去掉各自的深色顶栏；登录页重做 | 修改 |
| `admin-frontend/tests/theme.spec.ts` | 管理端 `useTheme` 单测 | 新建 |

## 颜色替换对照表（任务 4–8、10 通用）

组件 scoped 样式中的硬编码颜色按下表替换。判定依据是**用途**而不是色值本身；表里没有的颜色按最接近的用途归类。

| 原用途 / 典型值 | 替换为 |
|---|---|
| 白色背景 `white` `#fff` `#ffffff` | `var(--k-canvas)` |
| 浅灰/浅绿灰底 `#f7f9f8` `#fafbfa` `#f7f8f7` `#f6f9f7` `#f8fbf9` `#fbfcfb` | `var(--k-surface)` |
| 悬停底 `#f4f6f5` `#edf2ee` `#eef2ef` `#e9eeeb` | `var(--k-surface-hover)` |
| 选中底 `#eff2f0` `#e8eeea` | `var(--k-surface-active)` |
| 边框 `#e7eae9` `#dce5df` `#e6ebe7` `#e0e8e2` `#dce7df` `#dce3de` | `var(--k-line)` |
| 较深边框 `#cfe3d7` `#d8ded9` 及输入框 hover 边框 | `var(--k-line-strong)` |
| 标题/主文字 `#252a2a` `#233e30` `#34483b` `#294b38` `#2c553d` | `var(--k-ink)` |
| 次级文字 `#405448` `#58655e` `#66736b` `#3f5e49` | `var(--k-ink-2)` |
| 弱化文字 `#7a8780` `#858c8c` `#8a958e` `#8b978f` `#829087` | `var(--k-muted)` |
| 最淡文字/占位 `#929b95` `#a0a8a3` `#8b9990` | `var(--k-faint)` |
| 品牌绿文字/图标 `#37865a` `#267d50` `#246b48` `#317448` `#42875c` `#3a7a53` | `var(--k-green-deep)` |
| 品牌绿实心（进度条、圆点） `#18bc72` `#3a895d` | `var(--k-green)` |
| 浅绿底 `#eaf4ee` `#ddede2` `#edf7f0` `#d9e7de` `#eaf8f1` | `var(--k-green-soft)` |
| 绿色主按钮底色（`.primary` / `.primary-action` / 提交按钮） | 背景 `var(--k-primary)`，文字 `var(--k-on-primary)`，hover `var(--k-primary-hover)` |
| 红色文字 `#ad4949` `#d83931` `#b42318` | `var(--k-danger)` |
| 红色底 `#fff8f8` 及红色边框 `#f0dcdc` `#ecd5d5` | 底 `var(--k-danger-soft)`，边框 `var(--k-danger-line)` |
| 警告色（橙/黄） | `var(--k-warn)` / `var(--k-warn-soft)` |
| 投影 `box-shadow: … rgba(…)` | 按层级选 `var(--k-shadow-sm)` / `var(--k-shadow)` / `var(--k-shadow-float)` |
| 遮罩 `#14271c44` `rgba(0,0,0,.3~.5)` | `var(--k-overlay)` |
| 焦点 `0 0 0 3px #37865a12` 等 | `var(--k-focus-ring)` |
| PDF/DOCX 纸张本体的白色 | `var(--k-paper)`（两个主题都保持白纸） |
| 代码块背景 | `var(--k-code-bg)`，文字 `var(--k-code-ink)` |

圆角：`4–6px` → `var(--k-r-sm)`；`7–9px` → `var(--k-r)`；`10–14px` → `var(--k-r-lg)`；`999px`/`50%` 胶囊保持原样。

**每个样式任务的自检命令**（在 `frontend/` 下运行；输出应为空，若有剩余必须是注释里写明的例外）：

```bash
grep -nE "#[0-9a-fA-F]{3,8}\b|rgba?\(|\bwhite\b" <本任务改动的 .vue 文件>
```

---

### Task 1：用户端设计令牌与全局样式

**Files:**
- Rewrite: `frontend/src/styles/tokens.css`
- Create: `frontend/src/styles/markdown.css`
- Create: `frontend/src/styles/hljs-dark.css`
- Modify: `frontend/src/main.ts`

**Interfaces:**
- Produces：全部 `--k-*` 令牌（下方代码为唯一权威定义）；旧令牌名 `--kwiki-*` 作为别名；通用控件类 `.ui-button` `.ui-button.primary` `.ui-button.danger` `.ui-input` `.ui-icon` `.ui-error` `.ui-notice`；`<html data-theme="dark">` 时生效的深色值。

- [ ] **Step 1：重写 `frontend/src/styles/tokens.css`**

```css
/* kwiki 设计令牌：Mintlify 为主、Notion 温度。浅色为默认值，[data-theme="dark"] 覆盖为深色。 */
:root {
  color-scheme: light;

  /* 表面 */
  --k-canvas: #ffffff;
  --k-surface: #fafaf9;
  --k-surface-hover: #f3f3f1;
  --k-surface-active: #efefed;
  --k-paper: #ffffff;
  --k-overlay: rgba(10, 10, 10, 0.32);

  /* 细线 */
  --k-line: #e8e8e6;
  --k-line-strong: #dcdcd9;

  /* 文字 */
  --k-ink: #0a0a0a;
  --k-ink-2: #3a3a3c;
  --k-muted: #6b6b6e;
  --k-faint: #a8a8aa;

  /* 主操作：浅色黑底白字 */
  --k-primary: #0a0a0a;
  --k-primary-hover: #2a2a2c;
  --k-on-primary: #ffffff;

  /* 品牌绿：只用于选中、焦点、链接、品牌、成功 */
  --k-green: #10b981;
  --k-green-deep: #059669;
  --k-green-soft: #ecfdf5;

  /* 语义色 */
  --k-danger: #d45656;
  --k-danger-soft: #fdf2f2;
  --k-danger-line: #f3d4d4;
  --k-warn: #c37d0d;
  --k-warn-soft: #fdf6e7;

  /* 代码 */
  --k-code-bg: #1c1c1e;
  --k-code-ink: #e8e8e6;
  --k-inline-code-bg: #f3f3f1;

  /* 形状与层次 */
  --k-r-sm: 6px;
  --k-r: 8px;
  --k-r-lg: 12px;
  --k-r-pill: 999px;
  --k-shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.04);
  --k-shadow: 0 4px 12px rgba(0, 0, 0, 0.06);
  --k-shadow-float: 0 12px 32px rgba(0, 0, 0, 0.1);
  --k-focus-ring: 0 0 0 3px rgba(16, 185, 129, 0.18);
  --k-ease: 150ms cubic-bezier(0.2, 0, 0, 1);

  /* 字体 */
  --k-font: Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
  --k-font-mono: 'Geist Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;

  /* 布局尺寸 */
  --kwiki-nav-width: 232px;
  --kwiki-tree-width: 320px;
  --kwiki-desktop-min: 1024px;

  /* 旧令牌别名：保证尚未迁移的样式在两种主题下都正确 */
  --kwiki-green: var(--k-green);
  --kwiki-green-dark: var(--k-green-deep);
  --kwiki-green-soft: var(--k-green-soft);
  --kwiki-ink: var(--k-ink);
  --kwiki-muted: var(--k-muted);
  --kwiki-line: var(--k-line);
  --kwiki-soft: var(--k-surface);
  --kwiki-sidebar-bg: var(--k-surface);
  --kwiki-focus-outline: var(--k-green);
  --kwiki-bg: var(--k-canvas);
  --kwiki-panel: var(--k-canvas);
  --kwiki-border: var(--k-line);
  --kwiki-text: var(--k-ink);
  --kwiki-text-muted: var(--k-muted);
  --kwiki-primary: var(--k-green);
  --kwiki-selected-bg: var(--k-surface-active);
  --kwiki-hover-bg: var(--k-surface-hover);
  --kwiki-danger: var(--k-danger);
  --kwiki-radius: var(--k-r-sm);
  --kwiki-focus-ring: var(--k-focus-ring);

  /* Element Plus（PDF 工具条）变量映射 */
  --el-color-primary: var(--k-green-deep);
  --el-border-radius-base: var(--k-r);
  --el-font-family: var(--k-font);
}

/* 深色：层级参考 Linear，强调色保持品牌绿 */
[data-theme='dark'] {
  color-scheme: dark;

  --k-canvas: #0b0b0c;
  --k-surface: #111113;
  --k-surface-hover: #1a1a1d;
  --k-surface-active: #202024;
  --k-overlay: rgba(0, 0, 0, 0.6);

  --k-line: #232327;
  --k-line-strong: #34343a;

  --k-ink: #f5f5f4;
  --k-ink-2: #d4d4d2;
  --k-muted: #8a8a8f;
  --k-faint: #5c5c61;

  --k-primary: #f5f5f4;
  --k-primary-hover: #ffffff;
  --k-on-primary: #0a0a0a;

  --k-green: #34d399;
  --k-green-deep: #10b981;
  --k-green-soft: rgba(52, 211, 153, 0.12);

  --k-danger: #f07171;
  --k-danger-soft: rgba(240, 113, 113, 0.12);
  --k-danger-line: rgba(240, 113, 113, 0.28);
  --k-warn: #e0a33a;
  --k-warn-soft: rgba(224, 163, 58, 0.12);

  --k-code-bg: #161618;
  --k-code-ink: #e8e8e6;
  --k-inline-code-bg: #1a1a1d;

  --k-shadow-sm: 0 1px 2px rgba(0, 0, 0, 0.4);
  --k-shadow: 0 4px 12px rgba(0, 0, 0, 0.4);
  --k-shadow-float: 0 12px 32px rgba(0, 0, 0, 0.5);
  --k-focus-ring: 0 0 0 3px rgba(52, 211, 153, 0.22);
}

* {
  box-sizing: border-box;
}

html,
body {
  background: var(--k-canvas);
}

body {
  margin: 0;
  font-family: var(--k-font);
  font-size: 14px;
  line-height: 1.6;
  color: var(--k-ink);
  -webkit-font-smoothing: antialiased;
  transition: background-color var(--k-ease), color var(--k-ease);
}

::selection {
  background: var(--k-green-soft);
}

a {
  color: inherit;
}

button {
  font: inherit;
  color: inherit;
}

button:disabled {
  cursor: not-allowed;
  opacity: 0.5;
}

button,
a,
input,
textarea,
select {
  transition: background-color var(--k-ease), border-color var(--k-ease), box-shadow var(--k-ease),
    color var(--k-ease);
}

input,
textarea,
select {
  font: inherit;
  color: inherit;
}

input::placeholder,
textarea::placeholder {
  color: var(--k-faint);
}

button i,
a i {
  display: inline-block;
  flex-shrink: 0;
}

:focus-visible {
  outline: 2px solid var(--k-green);
  outline-offset: 2px;
  box-shadow: none;
  border-radius: var(--k-r-sm);
}

/* 细滚动条 */
* {
  scrollbar-width: thin;
  scrollbar-color: var(--k-line-strong) transparent;
}

/* 工作空间通用控件：统一视觉与焦点语言 */
.ui-icon {
  display: inline-grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border: 0;
  border-radius: var(--k-r-sm);
  background: transparent;
  color: var(--k-muted);
  cursor: pointer;
}

.ui-icon:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}

.ui-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-height: 36px;
  padding: 7px 14px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-canvas);
  color: var(--k-ink);
  text-decoration: none;
  cursor: pointer;
  font-weight: 500;
  box-shadow: var(--k-shadow-sm);
}

.ui-button:hover {
  border-color: var(--k-line-strong);
  background: var(--k-surface);
}

.ui-button.primary {
  color: var(--k-on-primary);
  background: var(--k-primary);
  border-color: var(--k-primary);
}

.ui-button.primary:hover {
  background: var(--k-primary-hover);
  border-color: var(--k-primary-hover);
}

.ui-button.danger {
  color: var(--k-danger);
  border-color: var(--k-danger-line);
}

.ui-button.danger:hover {
  background: var(--k-danger-soft);
}

.ui-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-canvas);
  outline: none;
}

.ui-input:hover {
  border-color: var(--k-line-strong);
}

.ui-input:focus {
  border-color: var(--k-green);
  box-shadow: var(--k-focus-ring);
}

.ui-error {
  padding: 10px 14px;
  border: 1px solid var(--k-danger-line);
  border-radius: var(--k-r);
  background: var(--k-danger-soft);
  color: var(--k-danger);
  font-size: 13px;
}

.ui-notice {
  padding: 10px 14px;
  border-radius: var(--k-r);
  background: var(--k-green-soft);
  color: var(--k-green-deep);
  font-size: 13px;
}
```

- [ ] **Step 2：新建 `frontend/src/styles/markdown.css`**（原 tokens.css 里的 `.markdown img` 与附件卡片规则迁到这里并改用令牌；再补 Notion 式阅读排版。`.markdown` / `.article` 两个类名保持不变。）

```css
/* Wiki 正文阅读排版：Notion 式节奏，颜色全部来自令牌。 */
.markdown img,
.markdown video,
.article img,
.article video {
  max-width: 100% !important;
  height: auto;
  object-fit: contain;
}

.markdown,
.article {
  color: var(--k-ink-2);
  font-size: 15px;
  line-height: 1.75;
}

.markdown :is(h1, h2, h3, h4),
.article :is(h1, h2, h3, h4) {
  color: var(--k-ink);
  font-weight: 600;
  line-height: 1.3;
}

.markdown h1,
.article h1 {
  margin: 1.6em 0 0.6em;
  font-size: 28px;
  letter-spacing: -0.5px;
}

.markdown h2,
.article h2 {
  margin: 1.8em 0 0.6em;
  padding-bottom: 0.3em;
  border-bottom: 1px solid var(--k-line);
  font-size: 22px;
  letter-spacing: -0.3px;
}

.markdown h3,
.article h3 {
  margin: 1.5em 0 0.5em;
  font-size: 18px;
  letter-spacing: -0.2px;
}

.markdown a,
.article a {
  color: var(--k-green-deep);
  text-decoration: underline;
  text-decoration-color: color-mix(in srgb, var(--k-green-deep) 35%, transparent);
  text-underline-offset: 3px;
}

.markdown a:hover,
.article a:hover {
  text-decoration-color: var(--k-green-deep);
}

.markdown blockquote,
.article blockquote {
  margin: 1em 0;
  padding: 2px 0 2px 16px;
  border-left: 3px solid var(--k-line-strong);
  color: var(--k-muted);
}

.markdown :not(pre) > code,
.article :not(pre) > code {
  padding: 2px 6px;
  border-radius: var(--k-r-sm);
  background: var(--k-inline-code-bg);
  color: var(--k-ink);
  font-family: var(--k-font-mono);
  font-size: 0.88em;
}

.markdown pre,
.article pre {
  margin: 1.2em 0;
  padding: 14px 16px;
  overflow: auto;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-code-bg);
  color: var(--k-code-ink);
  font-family: var(--k-font-mono);
  font-size: 13px;
  line-height: 1.6;
}

/* 代码块本身始终深色，highlight.js 的浅色主题背景需让位给代码块背景 */
.markdown pre code.hljs,
.article pre code.hljs {
  padding: 0;
  background: transparent;
}

.markdown table,
.article table {
  width: 100%;
  margin: 1.2em 0;
  border-collapse: collapse;
  font-size: 14px;
}

.markdown :is(th, td),
.article :is(th, td) {
  padding: 8px 12px;
  border: 1px solid var(--k-line);
  text-align: left;
}

.markdown th,
.article th {
  background: var(--k-surface);
  color: var(--k-ink);
  font-weight: 600;
}

.markdown hr,
.article hr {
  margin: 2em 0;
  border: 0;
  border-top: 1px solid var(--k-line);
}

/* 附件卡片 */
.kwiki-attachment-card,
a[data-kwiki-attachment='true'] {
  display: flex;
  align-items: center;
  gap: 12px;
  width: min(520px, 100%);
  margin: 12px 0;
  padding: 12px 14px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-surface);
  color: var(--k-ink);
  text-decoration: none;
}

.kwiki-attachment-card:hover,
a[data-kwiki-attachment='true']:hover {
  border-color: var(--k-line-strong);
  box-shadow: var(--k-shadow-sm);
}

.kwiki-attachment-card > span:first-child {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.kwiki-attachment-card strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.kwiki-attachment-card small {
  color: var(--k-muted);
}

.kwiki-attachment-download {
  margin-left: auto;
  color: var(--k-green-deep);
}
```

- [ ] **Step 3：新建 `frontend/src/styles/hljs-dark.css`**

说明：代码块在两种主题下都是深色底（`--k-code-bg`），因此语法高亮统一使用 github-dark 调色板，作用域限定在 `.markdown pre` / `.article pre` 内，覆盖 `render.ts` 引入的 `github.css`。文件名保留 `hljs-dark`，表示“深色代码块配色”。

```css
/* 代码块始终为深色底，统一使用 github-dark 调色板覆盖 highlight.js 的 github 浅色主题。 */
:is(.markdown, .article) pre .hljs {
  color: #e6edf3;
}

:is(.markdown, .article) pre :is(.hljs-doctag, .hljs-keyword, .hljs-meta .hljs-keyword, .hljs-template-tag, .hljs-template-variable, .hljs-type, .hljs-variable.language_) {
  color: #ff7b72;
}

:is(.markdown, .article) pre :is(.hljs-title, .hljs-title.class_, .hljs-title.class_.inherited__, .hljs-title.function_) {
  color: #d2a8ff;
}

:is(.markdown, .article) pre :is(.hljs-attr, .hljs-attribute, .hljs-literal, .hljs-meta, .hljs-number, .hljs-operator, .hljs-variable, .hljs-selector-attr, .hljs-selector-class, .hljs-selector-id) {
  color: #79c0ff;
}

:is(.markdown, .article) pre :is(.hljs-regexp, .hljs-string, .hljs-meta .hljs-string) {
  color: #a5d6ff;
}

:is(.markdown, .article) pre :is(.hljs-built_in, .hljs-symbol) {
  color: #ffa657;
}

:is(.markdown, .article) pre :is(.hljs-comment, .hljs-code, .hljs-formula) {
  color: #8b949e;
}

:is(.markdown, .article) pre :is(.hljs-name, .hljs-quote, .hljs-selector-tag, .hljs-selector-pseudo) {
  color: #7ee787;
}

:is(.markdown, .article) pre .hljs-subst {
  color: #e6edf3;
}

:is(.markdown, .article) pre .hljs-section {
  color: #1f6feb;
  font-weight: bold;
}

:is(.markdown, .article) pre .hljs-bullet {
  color: #f2cc60;
}

:is(.markdown, .article) pre .hljs-emphasis {
  color: #e6edf3;
  font-style: italic;
}

:is(.markdown, .article) pre .hljs-strong {
  color: #e6edf3;
  font-weight: bold;
}

:is(.markdown, .article) pre .hljs-addition {
  color: #aff5b4;
  background-color: #033a16;
}

:is(.markdown, .article) pre .hljs-deletion {
  color: #ffdcd7;
  background-color: #67060c;
}
```

- [ ] **Step 4：修改 `frontend/src/main.ts` 引入样式**

```ts
import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import { router } from './router';
import 'virtual:uno.css';
import 'element-plus/theme-chalk/dark/css-vars.css';
import './styles/tokens.css';
import './styles/markdown.css';
import './styles/hljs-dark.css';

createApp(App).use(createPinia()).use(router).mount('#app');
```

注意：`hljs-dark.css` 必须在 `render.ts`（引入 `github.css`）之后仍然生效。它的选择器带 `:is(.markdown, .article) pre` 前缀，优先级高于 `github.css` 的 `.hljs-*`，所以加载顺序无关。

- [ ] **Step 5：检查 `.markdown` / `.article` 选择器与组件内已有规则没有冲突**

Run：`cd frontend && grep -rn "\.markdown\|\.article" src --include=*.vue | grep -v "class=" | head -40`
PageReader.vue / PageEditor.vue 里对 `h1/h2/pre/table/blockquote` 的 scoped 规则会在 Task 5 统一清理。本步只记录清单，不修改。

- [ ] **Step 6：类型检查 + 单测**

Run：`cd frontend && npx vue-tsc --noEmit && npx vitest run`
Expected：vue-tsc 无输出；vitest 失败项与 Global Constraints 中的基线一致（不新增）。

- [ ] **Step 7：浏览器快速确认**

在已登录的 `http://localhost:5173/#/knowledge-bases` 打开开发者控制台，执行 `document.documentElement.dataset.theme='dark'`：页面背景、侧边栏、文字应整体变暗（组件内的硬编码颜色还没替换，会有残留浅色块，属于预期）。执行 `delete document.documentElement.dataset.theme` 还原。

- [ ] **Step 8：提交**

```bash
git add frontend/src/styles frontend/src/main.ts
git commit -m "feat(ui): 重建用户端设计令牌并加入深色令牌与阅读排版"
```

---

### Task 2：用户端 `useTheme` 与首屏防闪烁

**Files:**
- Create: `frontend/src/theme/useTheme.ts`
- Create: `frontend/tests/theme.spec.ts`
- Modify: `frontend/index.html`

**Interfaces:**
- Produces：
  - `type ThemeMode = 'light' | 'dark' | 'system'`
  - `type ResolvedTheme = 'light' | 'dark'`
  - `const THEME_STORAGE_KEY = 'kwiki-theme'`
  - `function useTheme(): { mode: Readonly<Ref<ThemeMode>>; resolved: ComputedRef<ResolvedTheme>; setMode(next: ThemeMode): void }`
  - `function resetThemeForTest(): void`（仅测试用）
  - 副作用：`document.documentElement` 的 `data-theme`、`class="dark"`、`style.colorScheme` 与 `resolved` 保持同步。

- [ ] **Step 1：写失败的测试 `frontend/tests/theme.spec.ts`**

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resetThemeForTest, THEME_STORAGE_KEY, useTheme } from '../src/theme/useTheme';

// 可手动触发 change 事件的 matchMedia 桩
function stubMatchMedia(initialDark: boolean) {
  const listeners = new Set<(event: MediaQueryListEvent) => void>();
  const media = {
    matches: initialDark,
    media: '(prefers-color-scheme: dark)',
    addEventListener: (_: string, fn: (event: MediaQueryListEvent) => void) => listeners.add(fn),
    removeEventListener: (_: string, fn: (event: MediaQueryListEvent) => void) => listeners.delete(fn),
  };
  vi.stubGlobal('matchMedia', vi.fn(() => media));
  return {
    setDark(dark: boolean) {
      media.matches = dark;
      listeners.forEach((fn) => fn({ matches: dark } as MediaQueryListEvent));
    },
  };
}

const root = () => document.documentElement;

describe('useTheme', () => {
  beforeEach(() => {
    localStorage.clear();
    resetThemeForTest();
    delete root().dataset.theme;
    root().classList.remove('dark');
  });
  afterEach(() => vi.unstubAllGlobals());

  it('默认跟随系统，并把系统外观写到 html 上', () => {
    stubMatchMedia(true);
    const { mode, resolved } = useTheme();
    expect(mode.value).toBe('system');
    expect(resolved.value).toBe('dark');
    expect(root().dataset.theme).toBe('dark');
    expect(root().classList.contains('dark')).toBe(true);
  });

  it('system 模式下实时跟随系统外观变化', () => {
    const system = stubMatchMedia(false);
    const { resolved } = useTheme();
    expect(root().dataset.theme).toBe('light');
    system.setDark(true);
    expect(resolved.value).toBe('dark');
    expect(root().dataset.theme).toBe('dark');
  });

  it('手动选择后持久化，并且不再受系统外观影响', () => {
    const system = stubMatchMedia(false);
    const { setMode, resolved } = useTheme();
    setMode('dark');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(resolved.value).toBe('dark');
    system.setDark(false);
    expect(root().dataset.theme).toBe('dark');
    setMode('light');
    expect(root().classList.contains('dark')).toBe(false);
  });

  it('启动时读取已保存的偏好，非法值回退为 system', () => {
    stubMatchMedia(false);
    localStorage.setItem(THEME_STORAGE_KEY, 'dark');
    expect(useTheme().mode.value).toBe('dark');
    resetThemeForTest();
    localStorage.setItem(THEME_STORAGE_KEY, 'purple');
    expect(useTheme().mode.value).toBe('system');
  });

  it('环境不支持 matchMedia 时按浅色处理', () => {
    vi.stubGlobal('matchMedia', undefined);
    expect(useTheme().resolved.value).toBe('light');
  });
});
```

- [ ] **Step 2：运行确认失败**

Run：`cd frontend && npx vitest run tests/theme.spec.ts`
Expected：FAIL，提示无法解析 `../src/theme/useTheme`。

- [ ] **Step 3：实现 `frontend/src/theme/useTheme.ts`**

```ts
import { computed, readonly, ref, type ComputedRef, type Ref } from 'vue';

export type ThemeMode = 'light' | 'dark' | 'system';
export type ResolvedTheme = 'light' | 'dark';

/** 与 index.html 中防闪烁脚本使用同一个键，修改时两处必须同步。 */
export const THEME_STORAGE_KEY = 'kwiki-theme';
const DARK_QUERY = '(prefers-color-scheme: dark)';

// 模块级单例：整个应用共享同一份主题状态
const mode = ref<ThemeMode>('system');
const systemDark = ref(false);
const resolved = computed<ResolvedTheme>(() =>
  mode.value === 'system' ? (systemDark.value ? 'dark' : 'light') : mode.value,
);
let started = false;
let media: MediaQueryList | null = null;

function readStoredMode(): ThemeMode {
  try {
    const value = localStorage.getItem(THEME_STORAGE_KEY);
    return value === 'light' || value === 'dark' || value === 'system' ? value : 'system';
  } catch {
    return 'system';
  }
}

function apply() {
  const root = document.documentElement;
  root.dataset.theme = resolved.value;
  root.classList.toggle('dark', resolved.value === 'dark');
  root.style.colorScheme = resolved.value;
}

function onSystemChange(event: MediaQueryListEvent) {
  systemDark.value = event.matches;
  apply();
}

function start() {
  if (started) return;
  started = true;
  mode.value = readStoredMode();
  media = typeof matchMedia === 'function' ? matchMedia(DARK_QUERY) : null;
  systemDark.value = media?.matches ?? false;
  media?.addEventListener('change', onSystemChange);
  apply();
}

function setMode(next: ThemeMode) {
  mode.value = next;
  try {
    localStorage.setItem(THEME_STORAGE_KEY, next);
  } catch {
    // 隐私模式等场景写入失败时只在本次会话内生效
  }
  apply();
}

export function useTheme(): {
  mode: Readonly<Ref<ThemeMode>>;
  resolved: ComputedRef<ResolvedTheme>;
  setMode: (next: ThemeMode) => void;
} {
  start();
  return { mode: readonly(mode), resolved, setMode };
}

/** 仅供测试：清空单例状态，下次 useTheme() 重新读取存储与系统外观。 */
export function resetThemeForTest() {
  media?.removeEventListener('change', onSystemChange);
  media = null;
  started = false;
  mode.value = 'system';
  systemDark.value = false;
}
```

- [ ] **Step 4：运行确认通过**

Run：`cd frontend && npx vitest run tests/theme.spec.ts`
Expected：5 passed。

- [ ] **Step 5：在 `frontend/index.html` 加防闪烁脚本**

```html
<!doctype html>
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>kwiki</title>
    <script>
      // 首屏渲染前写入主题，避免深色用户刷新时闪白；键名与 src/theme/useTheme.ts 保持一致
      (function () {
        var mode = null;
        try { mode = localStorage.getItem('kwiki-theme'); } catch (e) {}
        var dark = mode === 'dark' || (mode !== 'light' && !!window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
        var root = document.documentElement;
        root.dataset.theme = dark ? 'dark' : 'light';
        root.classList.toggle('dark', dark);
        root.style.colorScheme = dark ? 'dark' : 'light';
      })();
    </script>
  </head>
  <body>
    <div id="app"></div>
    <script type="module" src="/src/main.ts"></script>
  </body>
</html>
```

- [ ] **Step 6：类型检查 + 全量单测**

Run：`cd frontend && npx vue-tsc --noEmit && npx vitest run`
Expected：只有基线失败项。

- [ ] **Step 7：提交**

```bash
git add frontend/src/theme/useTheme.ts frontend/tests/theme.spec.ts frontend/index.html
git commit -m "feat(ui): 新增用户端主题模式管理与首屏防闪烁"
```

---

### Task 3：主题切换组件、全局侧边栏与组件库深色接入

**Files:**
- Create: `frontend/src/theme/ThemeSwitcher.vue`
- Create: `frontend/tests/theme-switcher.spec.ts`
- Modify: `frontend/src/App.vue`
- Modify: `frontend/src/features/workspace/WorkspaceShell.vue`（模板加入切换器；`<style>` 整段替换）
- Modify: `frontend/src/features/workspace/ProfilePage.vue`（新增「外观」小节；该文件其余样式在 Task 7 处理）

**Interfaces:**
- Consumes：`useTheme()`、`ThemeMode`（Task 2）。
- Produces：`<ThemeSwitcher variant="menu" | "segmented" :compact?="boolean" />`
  - `menu`：一个图标按钮（`aria-label="切换主题"`、`aria-haspopup="menu"`），点击后弹出 `role="menu"`，里面 3 个 `role="menuitemradio"`（`aria-checked` 标出当前项）；点外部或按 Esc 关闭。
  - `segmented`：`role="radiogroup"`（`aria-label="主题"`），3 个 `role="radio"` 按钮。
  - `compact`：只影响 `menu` 形态，为 true 时只显示图标，不显示文字。

- [ ] **Step 1：写失败的测试 `frontend/tests/theme-switcher.spec.ts`**

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/vue';
import ThemeSwitcher from '../src/theme/ThemeSwitcher.vue';
import { resetThemeForTest, THEME_STORAGE_KEY } from '../src/theme/useTheme';

describe('ThemeSwitcher', () => {
  beforeEach(() => {
    localStorage.clear();
    resetThemeForTest();
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })));
  });
  afterEach(() => vi.unstubAllGlobals());

  it('菜单形态：打开后可选择黑夜并持久化，选完关闭菜单', async () => {
    render(ThemeSwitcher, { props: { variant: 'menu' } });
    await fireEvent.click(screen.getByRole('button', { name: '切换主题' }));
    const system = screen.getByRole('menuitemradio', { name: /跟随系统/ });
    expect(system.getAttribute('aria-checked')).toBe('true');
    await fireEvent.click(screen.getByRole('menuitemradio', { name: /黑夜/ }));
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark');
    expect(document.documentElement.dataset.theme).toBe('dark');
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('菜单形态：按 Esc 关闭', async () => {
    render(ThemeSwitcher, { props: { variant: 'menu' } });
    await fireEvent.click(screen.getByRole('button', { name: '切换主题' }));
    await fireEvent.keyDown(screen.getByRole('menu'), { key: 'Escape' });
    expect(screen.queryByRole('menu')).toBeNull();
  });

  it('分段形态：三个单选项，点击白天生效', async () => {
    render(ThemeSwitcher, { props: { variant: 'segmented' } });
    expect(screen.getAllByRole('radio')).toHaveLength(3);
    await fireEvent.click(screen.getByRole('radio', { name: /白天/ }));
    expect(screen.getByRole('radio', { name: /白天/ }).getAttribute('aria-checked')).toBe('true');
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light');
  });
});
```

- [ ] **Step 2：运行确认失败**

Run：`cd frontend && npx vitest run tests/theme-switcher.spec.ts`
Expected：FAIL，提示无法解析 `ThemeSwitcher.vue`。

- [ ] **Step 3：实现 `frontend/src/theme/ThemeSwitcher.vue`**

```vue
<template>
  <div v-if="variant === 'segmented'" class="segmented" role="radiogroup" aria-label="主题">
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      role="radio"
      :aria-checked="mode === option.value"
      :class="{ active: mode === option.value }"
      @click="setMode(option.value)"
    >
      <i :class="option.icon" aria-hidden="true" /><span>{{ option.label }}</span>
    </button>
  </div>
  <div v-else ref="rootEl" class="theme-menu">
    <button
      type="button"
      class="ui-icon trigger"
      :class="{ wide: !compact }"
      aria-label="切换主题"
      aria-haspopup="menu"
      :aria-expanded="open"
      :title="`主题：${current.label}`"
      @click="open = !open"
    >
      <i :class="current.icon" aria-hidden="true" /><span v-if="!compact">{{ current.label }}</span>
    </button>
    <div v-if="open" class="menu" role="menu" aria-label="主题" @keydown.esc.stop="close">
      <button
        v-for="option in options"
        :key="option.value"
        type="button"
        role="menuitemradio"
        :aria-checked="mode === option.value"
        @click="pick(option.value)"
      >
        <i :class="option.icon" aria-hidden="true" /><span>{{ option.label }}</span>
        <i v-if="mode === option.value" class="i-lucide-check check" aria-hidden="true" />
      </button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue';
import { useTheme, type ThemeMode } from './useTheme';

withDefaults(defineProps<{ variant?: 'menu' | 'segmented'; compact?: boolean }>(), {
  variant: 'menu',
  compact: false,
});

const options: { value: ThemeMode; label: string; icon: string }[] = [
  { value: 'light', label: '白天', icon: 'i-lucide-sun' },
  { value: 'dark', label: '黑夜', icon: 'i-lucide-moon' },
  { value: 'system', label: '跟随系统', icon: 'i-lucide-monitor' },
];

const { mode, setMode } = useTheme();
const current = computed(() => options.find((option) => option.value === mode.value)!);
const open = ref(false);
const rootEl = ref<HTMLElement | null>(null);

function close() {
  open.value = false;
}

function pick(value: ThemeMode) {
  setMode(value);
  close();
}

// 点击组件外部时收起菜单
function onDocumentPointer(event: PointerEvent) {
  if (rootEl.value && !rootEl.value.contains(event.target as Node)) close();
}

watch(open, async (value) => {
  if (value) {
    document.addEventListener('pointerdown', onDocumentPointer);
    await nextTick();
    rootEl.value?.querySelector<HTMLElement>('[role="menuitemradio"][aria-checked="true"]')?.focus();
  } else {
    document.removeEventListener('pointerdown', onDocumentPointer);
  }
});

onBeforeUnmount(() => document.removeEventListener('pointerdown', onDocumentPointer));
</script>

<style scoped>
.theme-menu {
  position: relative;
}

.trigger.wide {
  width: auto;
  gap: 6px;
  padding: 0 8px;
  font-size: 12px;
}

.menu {
  position: absolute;
  bottom: calc(100% + 6px);
  left: 0;
  z-index: 90;
  display: grid;
  min-width: 148px;
  padding: 4px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  box-shadow: var(--k-shadow-float);
}

.menu button {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border: 0;
  border-radius: var(--k-r-sm);
  background: transparent;
  color: var(--k-ink-2);
  font-size: 13px;
  text-align: left;
  cursor: pointer;
}

.menu button:hover,
.menu button:focus-visible {
  background: var(--k-surface-hover);
  color: var(--k-ink);
  outline: none;
}

.menu .check {
  margin-left: auto;
  color: var(--k-green-deep);
}

.segmented {
  display: inline-flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-surface);
}

.segmented button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border: 0;
  border-radius: var(--k-r-sm);
  background: transparent;
  color: var(--k-muted);
  font-size: 13px;
  cursor: pointer;
}

.segmented button:hover {
  color: var(--k-ink);
}

.segmented button.active {
  background: var(--k-canvas);
  color: var(--k-ink);
  box-shadow: var(--k-shadow-sm);
}
</style>
```

- [ ] **Step 4：运行确认通过**

Run：`cd frontend && npx vitest run tests/theme-switcher.spec.ts`
Expected：3 passed。

- [ ] **Step 5：在 `frontend/src/App.vue` 接入 Naive UI 深色主题**（`ChunkPreview.vue` 的 `NModal` 会继承这个配置）

```vue
<template>
  <NConfigProvider abstract :theme="resolved === 'dark' ? darkTheme : null" :theme-overrides="themeOverrides">
    <WorkspaceShell v-if="route.meta.auth"><RouterView /></WorkspaceShell>
    <RouterView v-else />
  </NConfigProvider>
</template>

<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount } from 'vue';
import { RouterView, useRoute } from 'vue-router';
import { NConfigProvider, darkTheme, type GlobalThemeOverrides } from 'naive-ui';
import { useAuthStore } from './features/auth/store';
import { router } from './router';
import { useTheme } from './theme/useTheme';
import WorkspaceShell from './features/workspace/WorkspaceShell.vue';
const route = useRoute();
const { resolved } = useTheme();
// Naive UI 不读取 CSS 变量，这里按当前主题给出与令牌一致的具体值
const themeOverrides = computed<GlobalThemeOverrides>(() => ({
  common: {
    primaryColor: resolved.value === 'dark' ? '#34d399' : '#059669',
    primaryColorHover: resolved.value === 'dark' ? '#6ee7b7' : '#10b981',
    borderRadius: '8px',
    fontFamily: "Inter, ui-sans-serif, -apple-system, 'PingFang SC', 'Microsoft YaHei', sans-serif",
  },
}));

const auth = useAuthStore();
function onUnauthenticated() {
  const returnTo = router.currentRoute.value.fullPath;
  auth.logout();
  if (router.currentRoute.value.name !== 'login') void router.replace({ name: 'login', query: { returnTo } });
}
onMounted(() => {
  void auth.boot();
  window.addEventListener('kwiki:unauthenticated', onUnauthenticated);
});
onBeforeUnmount(() => window.removeEventListener('kwiki:unauthenticated', onUnauthenticated));
</script>
```

说明：`abstract` 让 `NConfigProvider` 不渲染额外的 `div` 包裹层，避免影响 Shell 的 `100dvh` 布局。

- [ ] **Step 6：修改 `WorkspaceShell.vue` 模板**：把账号区包进一个页脚，把切换器放在账号链接旁边。只替换 `<RouterLink to="/users/me" class="account">…</RouterLink>` 这一行：

```vue
      <footer class="nav-footer">
        <RouterLink to="/users/me" class="account"><span class="avatar">{{ (auth.user?.username || '?').slice(0, 1).toUpperCase() }}</span><span v-if="!collapsed" class="account-text"><strong>{{ auth.user?.displayName || auth.user?.username }}</strong><small>个人空间</small></span></RouterLink>
        <ThemeSwitcher variant="menu" compact />
      </footer>
```

在 `<script setup>` 里加：`import ThemeSwitcher from '../../theme/ThemeSwitcher.vue';`

原来的 `<i v-if="!collapsed" class="i-lucide-chevron-right" />` 去掉：页脚右侧已放切换器，箭头多余。

- [ ] **Step 7：整段替换 `WorkspaceShell.vue` 的 `<style scoped>`**

```css
.workspace-shell {
  height: 100dvh;
  display: grid;
  grid-template-columns: 232px minmax(0, 1fr);
  overflow: hidden;
  background: var(--k-canvas);
}

.workspace-shell.collapsed {
  grid-template-columns: 68px minmax(0, 1fr);
}

.global-nav {
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 16px 12px 12px;
  background: var(--k-surface);
  border-right: 1px solid var(--k-line);
}

.brand-row,
.brand {
  display: flex;
  align-items: center;
  gap: 9px;
}

.brand-row {
  justify-content: space-between;
  padding: 0 4px;
  min-height: 32px;
}

.brand {
  color: var(--k-ink);
  text-decoration: none;
  font-size: 19px;
  font-weight: 600;
  letter-spacing: -0.5px;
}

.brand img {
  width: 26px;
  height: 26px;
}

.brand-row .ui-icon {
  width: 28px;
  height: 28px;
}

.collapsed .brand-row {
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}

.new-chat {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  min-height: 36px;
  border: 1px solid var(--k-primary);
  border-radius: var(--k-r);
  background: var(--k-primary);
  color: var(--k-on-primary);
  font-weight: 500;
  cursor: pointer;
  box-shadow: var(--k-shadow-sm);
}

.new-chat:hover {
  background: var(--k-primary-hover);
}

.new-chat i,
.nav-item i {
  font-size: 16px;
  flex-shrink: 0;
}

nav {
  display: grid;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 34px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: var(--k-r);
  text-decoration: none;
  color: var(--k-ink-2);
  font-size: 13.5px;
}

.nav-item i {
  color: var(--k-muted);
}

.nav-item:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}

.nav-item.active {
  background: var(--k-canvas);
  border-color: var(--k-line);
  box-shadow: var(--k-shadow-sm);
  color: var(--k-ink);
  font-weight: 600;
}

.nav-item.active i {
  color: var(--k-green-deep);
}

.nav-item small {
  margin-left: auto;
  min-width: 18px;
  padding: 0 6px;
  border-radius: var(--k-r-pill);
  background: var(--k-green-soft);
  color: var(--k-green-deep);
  font-size: 11px;
  font-weight: 600;
  text-align: center;
}

.recent-list {
  min-height: 0;
  flex: 1;
  overflow: auto;
}

.recent-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px;
  margin-bottom: 6px;
}

.section-label {
  margin: 0;
  font-size: 11px;
  font-weight: 600;
  color: var(--k-faint);
  letter-spacing: 0.6px;
}

.recent-count {
  min-width: 18px;
  padding: 0 6px;
  border-radius: var(--k-r-pill);
  background: var(--k-surface-hover);
  color: var(--k-muted);
  font-size: 10px;
  text-align: center;
}

.recent-items {
  display: grid;
  gap: 1px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.recent-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 30px;
  padding: 4px 10px;
  border-radius: var(--k-r-sm);
  color: var(--k-muted);
  text-decoration: none;
}

.recent-item:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}

.recent-item.current {
  background: var(--k-surface-active);
  color: var(--k-ink);
  font-weight: 500;
}

.recent-icon {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  color: var(--k-faint);
}

.recent-item.current .recent-icon {
  color: var(--k-green-deep);
}

.recent-title {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  line-height: 20px;
}

.current-dot {
  width: 5px;
  height: 5px;
  flex: 0 0 5px;
  margin-left: auto;
  border-radius: 50%;
  background: var(--k-green);
}

.recent-empty {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 4px 6px;
  padding: 10px;
  border: 1px dashed var(--k-line-strong);
  border-radius: var(--k-r);
  color: var(--k-faint);
  font-size: 12px;
  line-height: 18px;
}

.recent-empty i {
  margin-top: 1px;
}

.nav-footer {
  margin-top: auto;
  display: flex;
  align-items: center;
  gap: 4px;
  padding-top: 12px;
  border-top: 1px solid var(--k-line);
}

.collapsed .nav-footer {
  flex-direction: column;
}

.account {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 6px;
  border-radius: var(--k-r);
  text-decoration: none;
  color: var(--k-ink);
}

.account:hover {
  background: var(--k-surface-hover);
}

.avatar {
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  border-radius: 50%;
  background: var(--k-green-soft);
  display: grid;
  place-items: center;
  color: var(--k-green-deep);
  font-size: 12px;
  font-weight: 700;
}

.account-text {
  min-width: 0;
}

.account strong,
.account small {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.account strong {
  font-size: 13px;
  font-weight: 600;
}

.account small {
  margin-top: 1px;
  font-size: 11px;
  color: var(--k-muted);
}

.workspace-body {
  min-width: 0;
  min-height: 0;
  overflow: auto;
  position: relative;
  background: var(--k-canvas);
}

.mobile-menu,
.nav-backdrop {
  display: none;
}

@media (max-width: 760px) {
  .workspace-shell,
  .workspace-shell.collapsed {
    grid-template-columns: minmax(0, 1fr);
  }

  .global-nav {
    display: none;
    position: fixed;
    inset: 0 auto 0 0;
    width: 232px;
    z-index: 80;
  }

  .global-nav.open {
    display: flex;
  }

  .mobile-menu {
    display: grid !important;
    position: fixed;
    top: 12px;
    left: 12px;
    z-index: 65;
    background: var(--k-canvas) !important;
    border: 1px solid var(--k-line) !important;
  }

  .nav-backdrop {
    display: block;
    position: fixed;
    inset: 0;
    background: var(--k-overlay);
    z-index: 70;
  }

  .collapse-button {
    display: none;
  }

  .workspace-body {
    padding-top: 48px;
  }
}
```

- [ ] **Step 8：`ProfilePage.vue` 新增「外观」小节**

先读模板结构：`sed -n '/<template>/,/<\/template>/p' frontend/src/features/workspace/ProfilePage.vue`。在页面现有的第一个设置分区（`<section>`）之后，插入一个与其同级、沿用相同分区类名的新分区：

```vue
<section class="appearance" aria-labelledby="appearance-title">
  <div>
    <h2 id="appearance-title">外观</h2>
    <p>选择白天、黑夜，或跟随系统设置自动切换。</p>
  </div>
  <ThemeSwitcher variant="segmented" />
</section>
```

如果现有分区用的是别的类名（比如 `.panel`），就把 `class="appearance"` 改成 `class="<那个类名> appearance"`，让它继承卡片外观。在 `<script setup>` 中加入 `import ThemeSwitcher from '../../theme/ThemeSwitcher.vue';`，并在 `<style scoped>` 末尾追加：

```css
.appearance {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.appearance h2 {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 600;
  color: var(--k-ink);
}

.appearance p {
  margin: 0;
  font-size: 13px;
  color: var(--k-muted);
}
```

- [ ] **Step 9：运行现有布局相关测试 + 全量**

Run：`cd frontend && npx vue-tsc --noEmit && npx vitest run`
Expected：只有基线失败项。如果 `layout.spec.ts` / `recent-visits.spec.ts` / `profile.spec.ts` 依赖被删掉的 `i-lucide-chevron-right` 或 DOM 层级，按新结构更新断言（保持断言意图不变）。

- [ ] **Step 10：浏览器验证**

在 `http://localhost:5173/#/knowledge-bases` 下：
1. 点侧边栏底部的主题按钮 → 选「黑夜」→ 侧边栏、新聊天按钮（反转为白底黑字）、导航选中项正确；刷新页面无闪白。
2. 选「跟随系统」→ 切换 macOS 外观，页面实时跟着变。
3. 收起侧边栏 → 页脚改为纵向排列，切换器仍可用。
4. 个人空间页能看到「外观」分段控件，并与侧边栏菜单的状态同步。

- [ ] **Step 11：提交**

```bash
git add frontend/src/theme frontend/tests/theme-switcher.spec.ts frontend/src/App.vue frontend/src/features/workspace/WorkspaceShell.vue frontend/src/features/workspace/ProfilePage.vue
git commit -m "feat(ui): 新增主题切换入口并重塑全局侧边栏"
```

---

### Task 4：登录页、知识库列表与知识库设置

**Files:**
- Modify: `frontend/src/features/auth/AuthPage.vue`（`<style>`）
- Modify: `frontend/src/features/workspace/KnowledgeBasePage.vue`（`<style>`）
- Modify: `frontend/src/features/workspace/KnowledgeBaseSettings.vue`（`<style>`）

**Interfaces:**
- Consumes：Task 1 的令牌、对照表。模板不改。

- [ ] **Step 1：AuthPage 样式**。按对照表替换全部颜色，然后确保以下规则存在（与现有同名规则合并，以这里为准）：

```css
.auth-page {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(1200px 600px at 20% -10%, var(--k-auth-sky), transparent 60%),
    radial-gradient(900px 500px at 100% 110%, var(--k-auth-sand), transparent 60%),
    var(--k-canvas);
}

.auth-card {
  width: min(400px, 100%);
  padding: 32px;
  border: 1px solid var(--k-line);
  border-radius: 16px;
  background: var(--k-canvas);
  box-shadow: var(--k-shadow-float);
}

.auth-card h1 {
  margin: 20px 0 6px;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.5px;
  color: var(--k-ink);
}

.logo {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--k-r-sm);
  background: var(--k-green);
  /* 浅色下为白字、深色下为近黑字，两种主题在绿底上都清晰 */
  color: var(--k-canvas);
  font-weight: 700;
}

.submit {
  width: 100%;
  min-height: 40px;
  border: 0;
  border-radius: var(--k-r);
  background: var(--k-primary);
  color: var(--k-on-primary);
  font-weight: 600;
  cursor: pointer;
}

.submit:hover:not(:disabled) {
  background: var(--k-primary-hover);
}

.switch {
  color: var(--k-green-deep);
}
```

在 `frontend/src/styles/tokens.css` 的 `:root` 中追加：`--k-auth-sky: #e3eef8; --k-auth-sand: #f7efe2;`；在 `[data-theme='dark']` 中追加：`--k-auth-sky: rgba(56, 189, 248, 0.08); --k-auth-sand: rgba(52, 211, 153, 0.06);`。输入框的边框、焦点用 `var(--k-line)` / `var(--k-green)` + `var(--k-focus-ring)`。

- [ ] **Step 2：KnowledgeBasePage 样式**。按对照表替换，然后以下列规则为准：

```css
.kb-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 40px 40px 80px;
}

.eyebrow {
  margin: 0 0 6px;
  font-size: 12px;
  font-weight: 500;
  color: var(--k-green-deep);
}

.page-heading h1 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  font-size: 28px;
  font-weight: 600;
  letter-spacing: -0.5px;
  color: var(--k-ink);
}

.base-count {
  padding: 1px 8px;
  border-radius: var(--k-r-pill);
  background: var(--k-surface-hover);
  color: var(--k-muted);
  font-size: 12px;
  font-weight: 500;
  letter-spacing: 0;
}

.sub {
  margin: 6px 0 0;
  color: var(--k-muted);
}

.primary-action {
  background: var(--k-primary) !important;
  border-color: var(--k-primary) !important;
  color: var(--k-on-primary) !important;
}

.primary-action:hover {
  background: var(--k-primary-hover) !important;
}

.base-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 16px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.base-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 188px;
  padding: 18px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  cursor: pointer;
  transition: border-color var(--k-ease), box-shadow var(--k-ease), transform var(--k-ease);
}

.base-card:hover,
.base-card:focus-visible {
  border-color: var(--k-line-strong);
  box-shadow: var(--k-shadow);
  transform: translateY(-1px);
}

.library-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: var(--k-r);
  background: var(--k-green-soft);
  color: var(--k-green-deep);
  font-size: 18px;
}

.role-badge {
  padding: 2px 8px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-pill);
  color: var(--k-muted);
  font-size: 11px;
}

.card-title {
  margin-top: 6px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.2px;
  color: var(--k-ink);
}

.card-description {
  flex: 1;
  margin: 0;
  color: var(--k-muted);
  font-size: 13px;
  line-height: 1.55;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.card-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  padding-top: 10px;
  border-top: 1px solid var(--k-line);
  opacity: 0.72;
  transition: opacity var(--k-ease);
}

.base-card:hover .card-actions,
.base-card:focus-within .card-actions {
  opacity: 1;
}

.modal {
  background: var(--k-overlay);
}

.modal-card {
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
  box-shadow: var(--k-shadow-float);
}
```

说明：设计文档里写的是“底部操作 hover 时显现”。完全隐藏会让触屏用户找不到操作，所以这里改为默认 0.72 透明度、hover 或键盘聚焦时变为 1，属于对设计文档的合理细化。`.card-actions` 中原有的 `display` / `span` 占位等布局规则保留。

- [ ] **Step 3：KnowledgeBaseSettings 样式**：只做对照表替换；页面容器以 `max-width: 760px; margin: 0 auto; padding: 40px;` 为准，标题 24px/600/−0.4px。

- [ ] **Step 4：自检硬编码颜色**

Run：`cd frontend && grep -nE "#[0-9a-fA-F]{3,8}\b|rgba?\(|\bwhite\b" src/features/auth/AuthPage.vue src/features/workspace/KnowledgeBasePage.vue src/features/workspace/KnowledgeBaseSettings.vue`
Expected：无输出。

- [ ] **Step 5：类型检查 + 单测**：`cd frontend && npx vue-tsc --noEmit && npx vitest run`，只允许出现基线失败。

- [ ] **Step 6：浏览器验证**：登录页（先退出登录，或新开隐身窗口）、知识库列表、新建知识库弹窗、知识库设置页，各在白天、黑夜下截图检查；再把窗口调到 375 宽，确认网格变成单列。

- [ ] **Step 7：提交**

```bash
git add frontend/src/features/auth/AuthPage.vue frontend/src/features/workspace/KnowledgeBasePage.vue frontend/src/features/workspace/KnowledgeBaseSettings.vue frontend/src/styles/tokens.css
git commit -m "feat(ui): 重塑登录页与知识库列表、设置页样式"
```

---

### Task 5：Wiki 工作区（目录树、阅读、编辑、预览）

**Files（均只改 `<style>`，除非另有说明）：**
- `frontend/src/features/wiki/components/WikiWorkspaceLayout.vue`
- `frontend/src/features/wiki/components/WikiTree.vue`
- `frontend/src/features/wiki/components/WikiSearch.vue`
- `frontend/src/features/wiki/components/WorkspacePage.vue`
- `frontend/src/features/wiki/components/WikiSummaryPanel.vue`
- `frontend/src/features/wiki/components/PageReader.vue`
- `frontend/src/features/wiki/components/PageEditor.vue`
- `frontend/src/features/wiki/components/MarkdownEditorAdapter.vue`
- `frontend/src/features/wiki/components/PageHeaderActions.vue`
- `frontend/src/features/wiki/components/RevisionHistoryDrawer.vue`
- `frontend/src/features/wiki/components/ArchiveConfirmDialog.vue`
- `frontend/src/features/wiki/components/SourcePreview.vue`
- `frontend/src/features/wiki/components/PdfSourceViewer.vue`
- `frontend/src/features/wiki/components/MediaBlock.vue`

**Interfaces:**
- Consumes：令牌、`markdown.css` 中的 `.markdown` / `.article` 排版。

- [ ] **Step 1：WikiWorkspaceLayout**。按对照表替换，并以下列规则为准：目录栏 `background: var(--k-surface); border-right: 1px solid var(--k-line);`，宽度用 `var(--kwiki-tree-width)`；面包屑栏 `height: 48px; border-bottom: 1px solid var(--k-line); color: var(--k-muted); font-size: 13px;`，当前项 `color: var(--k-ink)`。

- [ ] **Step 2：WikiTree 选中态**：找到当前页对应的选中类（`grep -n "active\|selected\|current" src/features/wiki/components/WikiTree.vue`），改为：

```css
/* 选中项：白底 + 左侧 2px 品牌绿竖线 */
.tree-row.active {
  position: relative;
  background: var(--k-canvas);
  color: var(--k-ink);
  font-weight: 500;
  box-shadow: var(--k-shadow-sm);
}

.tree-row.active::before {
  content: '';
  position: absolute;
  left: 0;
  top: 6px;
  bottom: 6px;
  width: 2px;
  border-radius: 2px;
  background: var(--k-green);
}
```

其中 `.tree-row.active` 换成实际的选择器（读文件后确定）。行高统一 `min-height: 30px; border-radius: var(--k-r-sm); font-size: 13px;`，hover 用 `var(--k-surface-hover)`。

- [ ] **Step 3：PageReader 阅读区**：
  1. 正文容器（包住 `.markdown` 或 `.article` 的那个元素）设为 `max-width: 760px; margin: 0 auto; padding: 40px 32px 96px;`。
  2. 页面标题 `font-size: 32px; font-weight: 700; letter-spacing: -0.8px; color: var(--k-ink); line-height: 1.2;`，元信息行 `font-size: 13px; color: var(--k-muted);`。
  3. **删除** scoped 样式中针对正文 `h1/h2/h3/pre/code/blockquote/table/th/td/hr` 的规则（已由 `markdown.css` 统一接管），其余规则按对照表替换。
  4. 如果 scoped 规则是用 `:deep(...)` 写的，同样删除。

- [ ] **Step 4：PageEditor + MarkdownEditorAdapter**：同样删除正文排版的重复规则；编辑区 `background: var(--k-canvas)`，源码区字体用 `var(--k-font-mono)`，工具条 `border-bottom: 1px solid var(--k-line); background: var(--k-surface);`，其余按对照表替换。保存按钮（主操作）用黑/白主按钮配色。

- [ ] **Step 5：SourcePreview + PdfSourceViewer**：外围画布 `background: var(--k-surface)`，PDF/DOCX 页面本体保持 `background: var(--k-paper)`（在规则上方写一行中文注释「纸张本体两种主题都保持白色」）。工具条按对照表替换。PDF 工具条上的 Element Plus 组件已由 Task 1 的 dark css-vars 与 `--el-color-primary` 自动适配，这里不再单独处理。

- [ ] **Step 6：其余文件**（WikiSearch、WorkspacePage、WikiSummaryPanel、PageHeaderActions、RevisionHistoryDrawer、ArchiveConfirmDialog、MediaBlock）：按对照表替换；抽屉和弹窗统一 `background: var(--k-canvas); border: 1px solid var(--k-line); border-radius: var(--k-r-lg); box-shadow: var(--k-shadow-float);`，遮罩用 `var(--k-overlay)`；危险确认按钮用 `var(--k-danger)` 实心底 + 白字（在深色下文字用 `var(--k-canvas)`）。WorkspacePage 中「新建文档」这类主按钮按主按钮配色处理。

- [ ] **Step 7：自检**

Run：`cd frontend && grep -nE "#[0-9a-fA-F]{3,8}\b|rgba?\(|\bwhite\b" src/features/wiki/components/*.vue`
Expected：只剩下带有中文注释说明的例外（例如 PDF 高亮层颜色）；其余一律为空。

- [ ] **Step 8：类型检查 + 单测**：`cd frontend && npx vue-tsc --noEmit && npx vitest run`，只允许出现基线失败。`markdown.spec.ts`、`search-tree.spec.ts`、`source-preview.spec.ts` 必须通过。

- [ ] **Step 9：浏览器验证**：打开知识库「Test」→ 文档「这是一个wiki」，分别检查阅读、编辑、修订历史抽屉、来源预览（如有 PDF），各在白天和黑夜下截图；确认代码块两种主题下都是深色底并有高亮，表格和引用块正常。

- [ ] **Step 10：提交**

```bash
git add frontend/src/features/wiki/components
git commit -m "feat(ui): 重塑 Wiki 工作区目录树、阅读与编辑样式"
```

---

### Task 6：聊天与检索面板

**Files（只改 `<style>`）：**
- `frontend/src/features/workspace/ConversationPage.vue`
- `frontend/src/features/workspace/ConversationMessages.vue`
- `frontend/src/features/workspace/ConversationLauncher.vue`
- `frontend/src/features/workspace/KnowledgeScopeDialog.vue`
- `frontend/src/features/wiki/components/AgenticAnswerPanel.vue`
- `frontend/src/features/wiki/components/RetrievalActivity.vue`
- `frontend/src/features/wiki/components/ThinkingStream.vue`
- `frontend/src/features/wiki/components/WikiInteractionPanel.vue`
- `frontend/src/features/wiki/components/ChunkPreview.vue`

- [ ] **Step 1：ConversationLauncher 悬浮按钮与浮窗**（以下为准，其余按对照表替换）：

```css
.launcher {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 40px;
  padding: 0 16px;
  border: 1px solid var(--k-primary);
  border-radius: var(--k-r-pill);
  background: var(--k-primary);
  color: var(--k-on-primary);
  font-weight: 500;
  box-shadow: var(--k-shadow-float);
  cursor: pointer;
}

.launcher:hover {
  background: var(--k-primary-hover);
}

.chat-float {
  border: 1px solid var(--k-line);
  border-radius: 16px;
  background: var(--k-canvas);
  box-shadow: var(--k-shadow-float);
}

.float-mark {
  color: var(--k-green-deep);
}
```

`.launcher` 和 `.chat-float` 原有的定位规则（`position: fixed; right/bottom; z-index`）保留不动。

- [ ] **Step 2：ConversationMessages 输入框与消息**：
  - 输入框容器：`border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas); box-shadow: var(--k-shadow);`；获得焦点时（`:focus-within`）`border-color: var(--k-line-strong); box-shadow: var(--k-shadow), var(--k-focus-ring);`。
  - 发送按钮：`background: var(--k-primary); color: var(--k-on-primary); border-radius: var(--k-r);`。
  - 用户消息气泡：`background: var(--k-surface-hover); color: var(--k-ink); border-radius: var(--k-r-lg);`。
  - 助手消息正文沿用 `.markdown` 排版。
  - 建议卡片：`border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas);`，hover 时 `border-color: var(--k-line-strong); box-shadow: var(--k-shadow-sm);`，图标 `color: var(--k-green-deep)`。
  - 欢迎区标题：`font-size: 28px; font-weight: 600; letter-spacing: -0.5px;`。

- [ ] **Step 3：ConversationPage**：会话列表栏与全局侧边栏风格一致（`background: var(--k-surface)`，选中项白底 + 细线），其余按对照表替换。

- [ ] **Step 4：其余文件**：按对照表替换。KnowledgeScopeDialog（109 处颜色，最多）逐条处理，勾选态用 `var(--k-green-deep)`，已选 chip 用 `var(--k-green-soft)` 底 + `var(--k-green-deep)` 字。RetrievalActivity 和 ThinkingStream 的时间线圆点用 `var(--k-green)`，连线用 `var(--k-line)`。

- [ ] **Step 5：自检**

Run：`cd frontend && grep -nE "#[0-9a-fA-F]{3,8}\b|rgba?\(|\bwhite\b" src/features/workspace/Conversation*.vue src/features/workspace/KnowledgeScopeDialog.vue src/features/wiki/components/{AgenticAnswerPanel,RetrievalActivity,ThinkingStream,WikiInteractionPanel,ChunkPreview}.vue`
Expected：无输出。

- [ ] **Step 6：类型检查 + 单测**：`cd frontend && npx vue-tsc --noEmit && npx vitest run`，只允许基线失败。`agentic-panel`、`floating-history`、`knowledge-scope-dialog`、`retrieval-activity`、`conversation-store` 必须通过。

- [ ] **Step 7：e2e 视觉用例**：`cd frontend && npx playwright test e2e/scope-dialog-visual.spec.ts`。如果它是截图对比而失败，确认差异来自预期的视觉变化后，用 `--update-snapshots` 更新快照，并在提交说明里写明。

- [ ] **Step 8：浏览器验证**：打开聊天页、悬浮「问问 kwiki」、知识范围选择弹窗，在白天、黑夜下各截一张图；提一个问题，确认检索活动面板和思考流的显示正常。

- [ ] **Step 9：提交**

```bash
git add frontend/src/features/workspace frontend/src/features/wiki/components frontend/e2e
git commit -m "feat(ui): 重塑聊天、悬浮助手与检索面板样式"
```

---

### Task 7：回收站、消息中心、个人空间与其余页面

**Files（只改 `<style>`）：**
- `frontend/src/features/workspace/TrashPage.vue`
- `frontend/src/features/workspace/NotificationCenter.vue`
- `frontend/src/features/workspace/ProfilePage.vue`
- `frontend/src/features/workspace/CollaborationPanel.vue`
- `frontend/src/features/workspace/InvitePage.vue`
- `frontend/src/features/workspace/TransferPage.vue`
- `frontend/src/features/workspace/FeaturePage.vue`

- [ ] **Step 1：统一页面骨架**：每个页面的根容器改为 `max-width: 960px; margin: 0 auto; padding: 40px 40px 80px;`（TrashPage 列表较宽，用 1200px），页面标题 `font-size: 28px; font-weight: 600; letter-spacing: -0.5px; color: var(--k-ink);`，副标题 `color: var(--k-muted)`。

- [ ] **Step 2：列表与表格**：行分隔 `border-bottom: 1px solid var(--k-line)`，表头 `background: var(--k-surface); color: var(--k-muted); font-size: 12px; font-weight: 600;`，行 hover `background: var(--k-surface-hover)`，复选框的选中色为 `accent-color: var(--k-green-deep)`。批量永久删除按钮用危险按钮样式。

- [ ] **Step 3：空状态**：图标容器为 `48px` 圆角方块，`background: var(--k-surface-hover); color: var(--k-faint);`，标题 15px/600，说明 13px `var(--k-muted)`。

- [ ] **Step 4：ProfilePage**：其余 83 处颜色按对照表替换，分区卡片为 `border: 1px solid var(--k-line); border-radius: var(--k-r-lg); padding: 20px 24px;`，Task 3 新增的「外观」分区与其他分区视觉一致。

- [ ] **Step 5：自检**

Run：`cd frontend && grep -nE "#[0-9a-fA-F]{3,8}\b|rgba?\(|\bwhite\b" src/features/workspace/{TrashPage,NotificationCenter,ProfilePage,CollaborationPanel,InvitePage,TransferPage,FeaturePage}.vue`
Expected：无输出。

- [ ] **Step 6：全局兜底扫描**

Run：`cd frontend && grep -rnE "#[0-9a-fA-F]{3,8}\b|rgba?\(|\bwhite\b" src --include=*.vue | grep -v "^\s*//"`
Expected：只剩带中文注释说明的例外。若还有遗漏的文件，按对照表补齐。

- [ ] **Step 7：类型检查 + 单测**：`cd frontend && npx vue-tsc --noEmit && npx vitest run`，只允许基线失败；`trash-delete`、`profile` 必须通过。

- [ ] **Step 8：浏览器验证**：回收站（含批量选择）、消息中心、个人空间，在白天、黑夜下各截一张图。

- [ ] **Step 9：提交**

```bash
git add frontend/src/features/workspace
git commit -m "feat(ui): 统一回收站、消息中心与个人空间等页面样式"
```

---

### Task 8：管理端令牌、`useTheme` 与 Element Plus 深色

**Files:**
- Create: `admin-frontend/src/styles/tokens.css`
- Create: `admin-frontend/src/theme/useTheme.ts`
- Create: `admin-frontend/tests/theme.spec.ts`
- Modify: `admin-frontend/src/main.ts`
- Modify: `admin-frontend/index.html`

**Interfaces:**
- Produces：与 Task 2 相同的 `useTheme()` API，存储键 `THEME_STORAGE_KEY = 'kwiki-admin-theme'`；与 Task 1 同值的 `--k-*` 令牌；Element Plus 的 `--el-*` 映射。

- [ ] **Step 1：写失败的测试 `admin-frontend/tests/theme.spec.ts`**：内容与 Task 2 Step 1 完全相同，只有两处不同：import 路径为 `../src/theme/useTheme`；在「手动选择后持久化」用例中额外断言 `expect(THEME_STORAGE_KEY).toBe('kwiki-admin-theme')`。完整代码如下：

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { resetThemeForTest, THEME_STORAGE_KEY, useTheme } from '../src/theme/useTheme';

// 可手动触发 change 事件的 matchMedia 桩
function stubMatchMedia(initialDark: boolean) {
  const listeners = new Set<(event: MediaQueryListEvent) => void>();
  const media = {
    matches: initialDark,
    media: '(prefers-color-scheme: dark)',
    addEventListener: (_: string, fn: (event: MediaQueryListEvent) => void) => listeners.add(fn),
    removeEventListener: (_: string, fn: (event: MediaQueryListEvent) => void) => listeners.delete(fn),
  };
  vi.stubGlobal('matchMedia', vi.fn(() => media));
  return {
    setDark(dark: boolean) {
      media.matches = dark;
      listeners.forEach((fn) => fn({ matches: dark } as MediaQueryListEvent));
    },
  };
}

const root = () => document.documentElement;

describe('管理端 useTheme', () => {
  beforeEach(() => {
    localStorage.clear();
    resetThemeForTest();
    delete root().dataset.theme;
    root().classList.remove('dark');
  });
  afterEach(() => vi.unstubAllGlobals());

  it('默认跟随系统', () => {
    stubMatchMedia(true);
    const { mode, resolved } = useTheme();
    expect(mode.value).toBe('system');
    expect(resolved.value).toBe('dark');
    expect(root().classList.contains('dark')).toBe(true);
  });

  it('system 模式下实时跟随系统外观变化', () => {
    const system = stubMatchMedia(false);
    const { resolved } = useTheme();
    system.setDark(true);
    expect(resolved.value).toBe('dark');
    expect(root().dataset.theme).toBe('dark');
  });

  it('手动选择后用管理端独立的键持久化', () => {
    stubMatchMedia(false);
    const { setMode } = useTheme();
    setMode('dark');
    expect(THEME_STORAGE_KEY).toBe('kwiki-admin-theme');
    expect(localStorage.getItem('kwiki-admin-theme')).toBe('dark');
    expect(root().dataset.theme).toBe('dark');
  });

  it('非法存储值回退为 system', () => {
    stubMatchMedia(false);
    localStorage.setItem(THEME_STORAGE_KEY, 'purple');
    expect(useTheme().mode.value).toBe('system');
  });
});
```

注意：管理端 `tests/setup.ts` 用 Map 模拟了 `localStorage`，并在 `afterEach` 里清空，与本测试兼容。

- [ ] **Step 2：运行确认失败**：`cd admin-frontend && npx vitest run tests/theme.spec.ts`，预期 FAIL（模块不存在）。

- [ ] **Step 3：实现 `admin-frontend/src/theme/useTheme.ts`**：代码与 Task 2 Step 3 完全一致，只改一行常量：

```ts
/** 与 index.html 中防闪烁脚本使用同一个键，修改时两处必须同步。 */
export const THEME_STORAGE_KEY = 'kwiki-admin-theme';
```

（执行者请把 Task 2 Step 3 的完整代码复制过来，再改这一行；管理端代码风格偏紧凑，但这个文件保持可读格式。）

- [ ] **Step 4：运行确认通过**：`cd admin-frontend && npx vitest run tests/theme.spec.ts`，预期 4 passed。

- [ ] **Step 5：新建 `admin-frontend/src/styles/tokens.css`**：把 Task 1 Step 1 代码中从文件开头到 `[data-theme='dark'] { … }` 结束的这一段原样复制过来，包括 Task 4 追加的 `--k-auth-sky` / `--k-auth-sand`。**不要**复制 `--kwiki-*` 别名和 `.ui-*` 控件。然后在文件末尾追加 Element Plus 映射：

```css
/* Element Plus 主题映射：主色为品牌绿，圆角、边框、文字与令牌对齐；html.dark 时叠加官方深色变量后再覆盖 */
:root,
html.dark {
  --el-color-primary: var(--k-green-deep);
  --el-color-primary-light-3: color-mix(in srgb, var(--k-green-deep) 70%, var(--k-canvas));
  --el-color-primary-light-5: color-mix(in srgb, var(--k-green-deep) 50%, var(--k-canvas));
  --el-color-primary-light-7: color-mix(in srgb, var(--k-green-deep) 30%, var(--k-canvas));
  --el-color-primary-light-8: color-mix(in srgb, var(--k-green-deep) 20%, var(--k-canvas));
  --el-color-primary-light-9: var(--k-green-soft);
  --el-color-primary-dark-2: var(--k-green-deep);
  --el-color-danger: var(--k-danger);
  --el-color-warning: var(--k-warn);
  --el-bg-color: var(--k-canvas);
  --el-bg-color-overlay: var(--k-canvas);
  --el-bg-color-page: var(--k-surface);
  --el-fill-color-blank: var(--k-canvas);
  --el-fill-color-light: var(--k-surface);
  --el-fill-color-lighter: var(--k-surface);
  --el-border-color: var(--k-line);
  --el-border-color-light: var(--k-line);
  --el-border-color-lighter: var(--k-line);
  --el-border-color-hover: var(--k-line-strong);
  --el-text-color-primary: var(--k-ink);
  --el-text-color-regular: var(--k-ink-2);
  --el-text-color-secondary: var(--k-muted);
  --el-text-color-placeholder: var(--k-faint);
  --el-border-radius-base: var(--k-r);
  --el-border-radius-small: var(--k-r-sm);
  --el-font-family: var(--k-font);
  --el-box-shadow-light: var(--k-shadow);
  --el-mask-color: var(--k-overlay);
}

/* 主按钮：浅色黑底白字，深色白底黑字 */
.el-button--primary {
  --el-button-bg-color: var(--k-primary);
  --el-button-border-color: var(--k-primary);
  --el-button-text-color: var(--k-on-primary);
  --el-button-hover-bg-color: var(--k-primary-hover);
  --el-button-hover-border-color: var(--k-primary-hover);
  --el-button-hover-text-color: var(--k-on-primary);
  --el-button-active-bg-color: var(--k-primary-hover);
  --el-button-active-border-color: var(--k-primary-hover);
}

/* 表格：细线 + 浅表头 */
.el-table {
  --el-table-header-bg-color: var(--k-surface);
  --el-table-header-text-color: var(--k-muted);
  --el-table-row-hover-bg-color: var(--k-surface-hover);
  --el-table-border-color: var(--k-line);
  --el-table-bg-color: var(--k-canvas);
  --el-table-tr-bg-color: var(--k-canvas);
}

html,
body {
  background: var(--k-canvas);
}

body {
  margin: 0;
  font-family: var(--k-font);
  font-size: 14px;
  color: var(--k-ink);
  -webkit-font-smoothing: antialiased;
}

* {
  box-sizing: border-box;
}
```

- [ ] **Step 6：修改 `admin-frontend/src/main.ts`**（保持该文件原有的紧凑风格）

```ts
import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import "element-plus/theme-chalk/dark/css-vars.css";
import "./styles/tokens.css";
import "./styles.css";
import App from "./App.vue";
import { router } from "./router";
createApp(App).use(createPinia()).use(router).use(ElementPlus).mount("#app");
```

- [ ] **Step 7：修改 `admin-frontend/index.html`**，在 `<title>` 后插入防闪烁脚本。脚本内容与 Task 2 Step 5 相同，只是键名改为 `'kwiki-admin-theme'`：

```html
<!doctype html><html lang="zh-CN"><head><meta charset="UTF-8"/><meta name="viewport" content="width=device-width,initial-scale=1.0"/><title>KWiki 管理后台</title><script>
// 首屏渲染前写入主题，避免深色用户刷新时闪白；键名与 src/theme/useTheme.ts 保持一致
(function(){var m=null;try{m=localStorage.getItem('kwiki-admin-theme')}catch(e){}var d=m==='dark'||(m!=='light'&&!!window.matchMedia&&window.matchMedia('(prefers-color-scheme: dark)').matches);var r=document.documentElement;r.dataset.theme=d?'dark':'light';r.classList.toggle('dark',d);r.style.colorScheme=d?'dark':'light'})();
</script></head><body><div id="app"></div><script type="module" src="/src/main.ts"></script></body></html>
```

- [ ] **Step 8：类型检查 + 单测**：`cd admin-frontend && npx vue-tsc --noEmit && npx vitest run`，预期 4 个文件全部通过（原 3 个加新增 1 个）。

- [ ] **Step 9：提交**

```bash
git add admin-frontend/src/styles admin-frontend/src/theme admin-frontend/tests/theme.spec.ts admin-frontend/src/main.ts admin-frontend/index.html
git commit -m "feat(admin): 新增管理端设计令牌、主题模式与 Element Plus 深色适配"
```

---

### Task 9：管理端侧边栏布局、登录页与页面样式

**Files:**
- Create: `admin-frontend/src/theme/ThemeSwitcher.vue`
- Create: `admin-frontend/src/components/AdminLayout.vue`
- Create: `admin-frontend/tests/admin-layout.spec.ts`
- Modify: `admin-frontend/src/App.vue`
- Modify: `admin-frontend/src/views/IndexManagementView.vue`（删除 `<header class="topbar">…</header>`，外层 `<div class="shell">` 换成 `<div>`）
- Modify: `admin-frontend/src/views/KnowledgeGraphView.vue`（同上）
- Modify: `admin-frontend/src/views/LoginView.vue`
- Rewrite: `admin-frontend/src/styles.css`

**Interfaces:**
- Consumes：管理端 `useTheme()`（Task 8）、`useAuth()`（`admin-frontend/src/auth.ts`，已有，含 `user`、`logout()`）。
- Produces：`<AdminLayout>` 默认插槽放页面内容；侧边栏包含两个 `RouterLink`（`/` 索引管理、`/knowledge-graphs` 知识图谱）、`<ThemeSwitcher>`、用户名和「退出」按钮。

- [ ] **Step 1：写失败的测试 `admin-frontend/tests/admin-layout.spec.ts`**

```ts
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/vue";
import { createPinia } from "pinia";
import { createMemoryHistory, createRouter } from "vue-router";
import AdminLayout from "../src/components/AdminLayout.vue";

const logout = vi.fn();
vi.mock("../src/auth", () => ({ useAuth: () => ({ user: { username: "root", admin: true }, logout }) }));

async function mount() {
  vi.stubGlobal("matchMedia", vi.fn(() => ({ matches: false, addEventListener() {}, removeEventListener() {} })));
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/", component: { template: "<div/>" } }, { path: "/knowledge-graphs", component: { template: "<div/>" } }, { path: "/login", component: { template: "<div/>" } }] });
  await router.push("/knowledge-graphs");
  render(AdminLayout, { slots: { default: "<p>页面内容</p>" }, global: { plugins: [createPinia(), router] } });
  return router;
}

describe("AdminLayout", () => {
  it("渲染两个导航入口并高亮当前页", async () => {
    await mount();
    expect(screen.getByRole("link", { name: /索引管理/ })).toBeTruthy();
    expect(screen.getByRole("link", { name: /知识图谱/ }).getAttribute("aria-current")).toBe("page");
    expect(screen.getByText("页面内容")).toBeTruthy();
  });

  it("提供主题分段控件与退出", async () => {
    const router = await mount();
    expect(screen.getAllByRole("radio")).toHaveLength(3);
    await fireEvent.click(screen.getByRole("button", { name: "退出" }));
    expect(logout).toHaveBeenCalled();
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe("/login"));
  });
});
```

- [ ] **Step 2：运行确认失败**：`cd admin-frontend && npx vitest run tests/admin-layout.spec.ts`，预期 FAIL（模块不存在）。

- [ ] **Step 3：实现 `admin-frontend/src/theme/ThemeSwitcher.vue`**（只有分段形态）

```vue
<template>
  <div class="segmented" role="radiogroup" aria-label="主题">
    <button
      v-for="option in options"
      :key="option.value"
      type="button"
      role="radio"
      :aria-checked="mode === option.value"
      :aria-label="option.label"
      :title="option.label"
      :class="{ active: mode === option.value }"
      @click="setMode(option.value)"
    >
      <svg viewBox="0 0 24 24" aria-hidden="true"><path :d="option.path" /></svg>
    </button>
  </div>
</template>

<script setup lang="ts">
import { useTheme, type ThemeMode } from "./useTheme";

// 管理端未接入 UnoCSS 图标，这里内联 lucide 的 sun / moon / monitor 路径
const options: { value: ThemeMode; label: string; path: string }[] = [
  { value: "light", label: "白天", path: "M12 3v2M12 19v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M3 12h2M19 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4M16 12a4 4 0 1 1-8 0 4 4 0 0 1 8 0Z" },
  { value: "dark", label: "黑夜", path: "M12 3a6 6 0 0 0 9 9 9 9 0 1 1-9-9Z" },
  { value: "system", label: "跟随系统", path: "M4 4h16v12H4zM8 20h8M12 16v4" },
];
const { mode, setMode } = useTheme();
</script>

<style scoped>
.segmented {
  display: flex;
  gap: 2px;
  padding: 3px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r);
  background: var(--k-surface);
}

.segmented button {
  flex: 1;
  display: grid;
  place-items: center;
  height: 26px;
  border: 0;
  border-radius: var(--k-r-sm);
  background: transparent;
  color: var(--k-muted);
  cursor: pointer;
}

.segmented button:hover {
  color: var(--k-ink);
}

.segmented button.active {
  background: var(--k-canvas);
  color: var(--k-ink);
  box-shadow: var(--k-shadow-sm);
}

svg {
  width: 15px;
  height: 15px;
  fill: none;
  stroke: currentColor;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}
</style>
```

- [ ] **Step 4：实现 `admin-frontend/src/components/AdminLayout.vue`**

```vue
<template>
  <div class="admin-shell">
    <aside class="side" aria-label="管理后台导航">
      <div class="brand"><span class="logo">k</span><strong>KWiki</strong><small>管理后台</small></div>
      <nav>
        <RouterLink to="/" class="nav-item" exact-active-class="active">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h10" /></svg>索引管理
        </RouterLink>
        <RouterLink to="/knowledge-graphs" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 6a2 2 0 1 0 0 .01M18 6a2 2 0 1 0 0 .01M12 18a2 2 0 1 0 0 .01M7.5 7.5l3.5 8.5M16.5 7.5 13 16M8 6h8" /></svg>知识图谱
        </RouterLink>
      </nav>
      <footer>
        <ThemeSwitcher />
        <div class="account">
          <span class="avatar">{{ (auth.user?.username || "?").slice(0, 1).toUpperCase() }}</span>
          <span class="name">{{ auth.user?.username }}</span>
          <button type="button" class="logout" @click="logout">退出</button>
        </div>
      </footer>
    </aside>
    <div class="content"><slot /></div>
  </div>
</template>

<script setup lang="ts">
import { RouterLink, useRouter } from "vue-router";
import { useAuth } from "../auth";
import ThemeSwitcher from "../theme/ThemeSwitcher.vue";

const auth = useAuth();
const router = useRouter();
function logout() {
  auth.logout();
  void router.push("/login");
}
</script>

<style scoped>
.admin-shell {
  min-height: 100vh;
  display: grid;
  grid-template-columns: 224px minmax(0, 1fr);
}

.side {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 18px 12px 12px;
  background: var(--k-surface);
  border-right: 1px solid var(--k-line);
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 6px;
}

.brand strong {
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.4px;
}

.brand small {
  padding: 1px 6px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-pill);
  color: var(--k-muted);
  font-size: 11px;
}

.logo {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border-radius: var(--k-r-sm);
  background: var(--k-green);
  color: var(--k-canvas);
  font-weight: 700;
}

nav {
  display: grid;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 34px;
  padding: 6px 10px;
  border: 1px solid transparent;
  border-radius: var(--k-r);
  color: var(--k-ink-2);
  text-decoration: none;
  font-size: 13.5px;
}

.nav-item:hover {
  background: var(--k-surface-hover);
  color: var(--k-ink);
}

.nav-item.active {
  background: var(--k-canvas);
  border-color: var(--k-line);
  box-shadow: var(--k-shadow-sm);
  color: var(--k-ink);
  font-weight: 600;
}

.nav-item svg {
  width: 16px;
  height: 16px;
  fill: none;
  stroke: var(--k-muted);
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.nav-item.active svg {
  stroke: var(--k-green-deep);
}

footer {
  margin-top: auto;
  display: grid;
  gap: 10px;
  padding-top: 12px;
  border-top: 1px solid var(--k-line);
}

.account {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 4px;
}

.avatar {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  background: var(--k-green-soft);
  color: var(--k-green-deep);
  font-size: 12px;
  font-weight: 700;
}

.name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
}

.logout {
  border: 0;
  background: transparent;
  color: var(--k-muted);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.logout:hover {
  color: var(--k-danger);
}

.content {
  min-width: 0;
}

@media (max-width: 900px) {
  .admin-shell {
    grid-template-columns: minmax(0, 1fr);
  }

  .side {
    position: static;
    height: auto;
    flex-direction: row;
    flex-wrap: wrap;
    align-items: center;
  }

  nav {
    grid-auto-flow: column;
  }

  footer {
    margin: 0 0 0 auto;
    border: 0;
    padding: 0;
    grid-auto-flow: column;
  }
}
</style>
```

注意：`RouterLink` 在精确匹配时会自动加 `aria-current="page"`，测试依赖这个行为。索引管理用 `exact-active-class`，避免它在 `/knowledge-graphs` 下也被高亮。

- [ ] **Step 5：运行确认通过**：`cd admin-frontend && npx vitest run tests/admin-layout.spec.ts`，预期 2 passed。

- [ ] **Step 6：修改 `admin-frontend/src/App.vue`**

```vue
<template>
  <router-view v-if="route.meta.public || !route.matched.length" />
  <AdminLayout v-else><router-view /></AdminLayout>
</template>
<script setup lang="ts">
import { useRoute } from "vue-router";
import AdminLayout from "./components/AdminLayout.vue";
import { useTheme } from "./theme/useTheme";
const route = useRoute();
// 登录页与路由尚未解析完成时不挂 AdminLayout，避免闪出侧边栏；主题在启动时即应用
useTheme();
</script>
```

- [ ] **Step 7：两个视图去掉顶栏**：在 `IndexManagementView.vue` 和 `KnowledgeGraphView.vue` 中删除整段 `<header class="topbar">…</header>`，并把外层 `<div class="shell">` 改为 `<div>`。删除后如果 `auth` 变量不再被使用，同时删掉它的 `import` 和声明，否则 vue-tsc 会报未使用。

- [ ] **Step 8：重写 `admin-frontend/src/styles.css`**（去掉 `.topbar` / `.topnav`，其余改用令牌）

```css
/* 管理端页面排版：细页头、指标卡、字段说明，颜色全部来自令牌 */
.page {
  max-width: 1400px;
  margin: 0 auto;
  padding: 32px 40px 64px;
}

.hero {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 24px;
  padding-bottom: 20px;
  border-bottom: 1px solid var(--k-line);
}

.hero h1 {
  margin: 0 0 6px;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.5px;
  color: var(--k-ink);
}

.hero .el-radio-group {
  flex-shrink: 0;
  padding-top: 4px;
}

.muted {
  color: var(--k-muted);
  line-height: 1.6;
}

.cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 24px;
}

.metric {
  padding: 16px 18px;
  border: 1px solid var(--k-line);
  border-radius: var(--k-r-lg);
  background: var(--k-canvas);
}

.metric strong {
  display: block;
  margin-top: 6px;
  font-size: 26px;
  font-weight: 600;
  letter-spacing: -0.6px;
  color: var(--k-ink);
  font-variant-numeric: tabular-nums;
}

.metric-label {
  display: block;
  font-size: 12px;
  font-weight: 500;
  color: var(--k-muted);
}

.metric-text {
  font-size: 16px !important;
  line-height: 1.4;
  letter-spacing: 0 !important;
  word-break: break-all;
}

.metric .hint {
  margin-top: 4px;
}

.hint {
  margin-top: 2px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--k-faint);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.range {
  min-width: 260px;
}

.danger-copy {
  color: var(--k-danger);
}

.dialog-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.self-center {
  align-self: center;
}

.tab-desc {
  margin: 0 0 12px;
  color: var(--k-muted);
}

.narrow-form {
  max-width: 640px;
}

.dialog-alert {
  margin-bottom: 14px;
}

.enable-guide {
  max-width: 560px;
  text-align: left;
}

.enable-guide pre {
  overflow: auto;
  padding: 12px 16px;
  border-radius: var(--k-r);
  background: var(--k-code-bg);
  color: var(--k-code-ink);
  font-family: var(--k-font-mono);
}

.enable-guide code {
  padding: 1px 5px;
  border-radius: 4px;
  background: var(--k-surface-hover);
  font-family: var(--k-font-mono);
}

/* 登录页 */
.login {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(1200px 600px at 20% -10%, var(--k-auth-sky), transparent 60%),
    radial-gradient(900px 500px at 100% 110%, var(--k-auth-sand), transparent 60%),
    var(--k-canvas);
}

.login-card {
  width: min(400px, 100%);
  padding: 32px;
  border: 1px solid var(--k-line);
  border-radius: 16px;
  background: var(--k-canvas);
  box-shadow: var(--k-shadow-float);
}

.login-card h1 {
  margin: 20px 0 6px;
  font-size: 24px;
  font-weight: 600;
  letter-spacing: -0.5px;
}

.login-card .sub {
  margin: 0 0 20px;
  color: var(--k-muted);
}

.login-card .el-button {
  width: 100%;
  height: 40px;
}

.login-brand {
  display: flex;
  align-items: center;
  gap: 8px;
}

.login-brand .logo {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border-radius: var(--k-r-sm);
  background: var(--k-green);
  color: var(--k-canvas);
  font-weight: 700;
}

@media (max-width: 900px) {
  .cards {
    grid-template-columns: 1fr 1fr;
  }

  .page {
    padding: 16px;
  }
}
```

- [ ] **Step 9：重写 `LoginView.vue` 模板**（`<script setup>` 不变）

```vue
<template><main class="login"><section class="login-card"><div class="login-brand"><span class="logo">k</span><strong>KWiki</strong></div><h1>管理后台</h1><p class="sub">索引版本与发布控制</p><el-alert v-if="error" :title="error" type="error" :closable="false" class="dialog-alert"/><el-form label-position="top" @submit.prevent="submit"><el-form-item label="用户名"><el-input v-model="username" autocomplete="username"/></el-form-item><el-form-item label="密码"><el-input v-model="password" type="password" show-password autocomplete="current-password"/></el-form-item><el-button native-type="submit" type="primary" :loading="busy">登录</el-button></el-form></section></main></template>
```

- [ ] **Step 10：自检**

Run：`cd admin-frontend && grep -rnE "#[0-9a-fA-F]{3,8}\b|rgba?\(|color:\s*white|style=\"color" src --include=*.vue --include=*.css | grep -v "styles/tokens.css"`
Expected：无输出。如果视图里还有内联的 `style="color:white"` 等（原来退出按钮上有），都要删除。

- [ ] **Step 11：类型检查 + 单测 + 构建**：`cd admin-frontend && npx vue-tsc --noEmit && npx vitest run && npx vite build`，预期全部通过（5 个测试文件）。

- [ ] **Step 12：浏览器验证**：在 `admin-frontend` 下运行 `npx vite --port 5174`（后台运行），打开 `http://localhost:5174/admin/`，用管理员账号登录（如果 `test` 账号没有管理员权限，请用户提供管理员测试账号或自行登录），检查登录页、索引管理、知识图谱在白天和黑夜下的表现（表格、标签、对话框、分页、加载遮罩），各截一张图。

- [ ] **Step 13：提交**

```bash
git add admin-frontend
git commit -m "feat(admin): 管理端改为浅色侧边栏布局并重塑登录页与页面样式"
```

---

### Task 10：整体验收

**Files:** 视需要修改前面任务涉及的文件（只做修补）。

- [ ] **Step 1：用户端构建**：`cd frontend && npx vue-tsc --noEmit && npx vitest run && npx vite build`。vitest 只允许基线失败；build 必须成功。

- [ ] **Step 2：用户端 e2e**：`cd frontend && npx playwright test`。失败时逐条判断：如果是选择器因 DOM 调整失效，就修选择器；如果是视觉快照差异，确认符合预期后更新快照。

- [ ] **Step 3：截图矩阵**（用户端 1440×900）：登录、知识库列表、Wiki 阅读、Wiki 编辑、聊天、悬浮助手、回收站、个人空间 × 白天 / 黑夜，共 16 张。另外在 375×812 下检查：知识库列表、Wiki 阅读、全局导航抽屉。

- [ ] **Step 4：主题行为检查**：
  1. 选择「跟随系统」后切换 macOS 外观，两个前端都会实时变化。
  2. 选择「黑夜」后强制刷新，没有白屏闪烁。
  3. 用户端和管理端的偏好互相独立（存储键不同）。

- [ ] **Step 5：对比度抽查**：用浏览器开发者工具检查深色下 `--k-muted` 文字在 `--k-canvas` 上的对比度（应 ≥ 4.5:1，`#8a8a8f` 在 `#0b0b0c` 上约为 5.9:1）、`--k-faint` 只用于占位符和装饰性文字。若发现正文使用了 `--k-faint`，改为 `--k-muted`。

- [ ] **Step 6：注释语言检查**：`git diff main --stat` 列出改动文件后，抽查新增注释，确认都是简体中文。

- [ ] **Step 7：提交修补（如有）**

```bash
git add -A frontend admin-frontend
git commit -m "fix(ui): 修补视觉重塑验收中发现的问题"
```
