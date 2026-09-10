## Context

`mediaBlocks.ts` 的 MEDIA_TAG 只匹配起始标签，`serializeMedia` 输出完整音视频标签，编辑适配器以扫描范围替换时留下旧闭合标签。此问题需要修复源码节点边界，而非在阅读页隐藏文本。

`@kk-2004/ui-components` 当前为 Vue 3 库，支持 `./components/*` 子入口，使用 preserveModules 与 cssCodeSplit，CSS 标为 sideEffects。现有 peerDependencies 包含 TDesign、图标和 marked，媒体子入口需要证明没有这些组件的运行时代码依赖；安装依赖与实际打包加载需分别检查。

## Goals / Non-Goals

**Goals:** 一个媒体展示接口用于源码卡片、预览、阅读页；统一加载状态、水印、下载交互；仅加载使用的媒体实现；修复媒体编辑往返。

**Non-Goals:** 自研播放器、DRM 平台、阻止开发者工具、阻止录屏、音频不可听水印、服务端烧录水印、改变附件索引策略。当前交付为提案，实施按组件库先行顺序执行。

## Decisions

### 1. 复用 Plyr，封装 Vue 生命周期

优先选 Plyr 原生包，不额外引入第三方 Vue 包装器。其官方文档明确支持 HTML5 音频和视频、可配置控件，适合统一两种媒体；初始化与 destroy 由本组件管理。图片使用浏览器图片加载和 decode API，不加载播放器。

比较：Artplayer 官方文档以视频播放器及扩展库为中心，可作为复杂视频需求的备选；本期不需要 HLS、FLV 或专业流媒体插件。直接使用原生 controls 依赖更少，但统一控制界面及水印全屏行为更受浏览器限制。Plyr 的具体版本、许可证与打包产物在实施时锁定并验证，不在提案中虚构体积指标。

研究依据（2026-09-09）：[Plyr 官方站](https://plyr.io/)、[Plyr 源码与配置文档](https://github.com/sampotts/plyr)、[Artplayer 官方选项](https://artplayer.org/document/en/start/option)。

### 2. KMediaViewer 接口与职责

提供 kind(image/audio/video)、src、alt/title、poster、intrinsicWidth/Height、align、width、timeoutMs(默认 10000)、allowDownload(默认 true)、watermark、resolveSource、downloadHandler；事件包含 ready/error/retry/download-request。resolveSource 接收稳定引用和取消信号，返回 URL 与可选尺寸，组件不依赖 kwiki API、令牌或 SDK。

watermark 默认关闭；开启后支持 text、opacity(0–1)、fontSize、color、rotate、gapX/gapY，校验范围，文本禁止作为 HTML 注入。水印覆盖整个展示区，pointer-events:none；音频水印只显示在播放器卡片。全屏由含水印的容器承载；配置水印时关闭会绕开覆盖层的原生 PiP/原生视频全屏入口，无法保持覆盖层的平台使用容器内全屏替代。

状态为 resolving/loading/ready/error。URL 解析成功不代表加载成功，图片完成 load 与 decode 后才显示；此前隐藏实际图片，保持同一容器占位。已知尺寸用 aspect-ratio 预留最终高度；未知尺寸使用固定初始框，在获知尺寸时最多一次调整，不能经历占位坍塌再回弹。失败/超时显示明确原因、重试；源码卡片的删除操作由编辑器提供且始终可用。切换源或卸载取消旧任务，旧事件不能覆盖新状态。音视频等待可播放事件，等待缓冲也显示状态，不预先下载完整视频。

图片默认居中，auto 为容器宽度 50%，最大 100%，窄屏保持可用；保留显式布局配置。编辑工具位于稳定容器，支持 hover 与 focus-within。

### 3. 下载开关的真实边界

allowDownload=false 隐藏下载按钮、禁用组件区域的下载右键菜单，设置浏览器支持的 nodownload 控制；不拦截整个页面键盘或 F12。该配置是交互策略，无法阻止网络面板、缓存或脚本提取已发送给浏览器的媒体。覆盖层水印同样不是防篡改或烧录水印。

kwiki 适配器沿用服务端访问鉴权和短期媒体 URL，不能将前端布尔值当作授权凭据。若未来要增加独立的下载权限，必须由服务端策略控制下载/导出端点，不能仅靠组件；本期不新增这套权限模型。外链也只能提供交互限制。更强保护需另行设计加密分发、许可证与 DRM，且仍不能保证绝对无法复制。

### 4. 子路径、懒加载及集成

先实现组件库 `components/KMediaViewer/index.ts` 的导出和类型，然后用本地打包产物在 kwiki 验证，最终采用可复现的固定版本依赖；不自动发布 npm。kwiki 使用动态导入 `@kk-2004/ui-components/components/KMediaViewer`，不从总入口导入。组件内部仅音视频分支动态加载 Plyr 与对应 CSS；图片分支不能触发播放器下载。构建需显式保证该子入口生成，即使总入口未导出也能被构建消费；媒体样式不引用组件库全局 CSS。

源码卡片、预览、阅读页共享该 Vue 组件；现有字符串 renderer 输出安全占位描述，由 Vue 管理实例挂载和销毁，不拼接第二个播放器，也不把 Vue 事件绑定到 v-html。kwiki URL 适配器继续解析 attachment://，不把短期 URL 保存进正文。Markdown/HTML 导出保持静态可移植媒体，不依赖组件运行时。

### 5. 完整节点往返与旧内容处理

扫描返回整个 audio/video 元素范围，支持换行属性与合法 source 子节点；忽略围栏代码、行内代码和转义文本。可采用现有 Markdown 解析设施配合有源位置的 HTML token 处理，避免全局正则清除闭合标签。统一序列化的属性转义与 URL 协议校验。

只对已识别媒体节点紧随的同名孤立闭合标签做兼容修复，代码及无法确定归属的内容保留。阅读不写数据库，明确保存时才持久化标准形式；不做批量历史正文改写。重复编辑、布局调整、保存重开得到稳定的一组标签。

## Risks / Trade-offs

- 播放器 CSS 或库入口带入额外依赖 → 用真实消费构建和浏览器 Network 检查图片页、音视频页与无媒体页。
- 未知图片尺寸无法预知最终高度 → 固定占位并缓存/传递可获得的尺寸，明确允许一次尺寸收敛，不承诺未知尺寸零位移。
- 不同浏览器全屏行为不同 → 验证 Chromium、Safari 的水印保留和降级路径。
- 历史孤立标签可能是用户刻意文本 → 限制识别范围、代码豁免、增加反例测试。

## Migration Plan

先交付组件库及可消费构建，再修复 kwiki scanner 并接入三种展示上下文。保留现有附件引用格式；部署只需前端依赖更新，无数据库迁移。回滚恢复前端与组件版本，规范化后的合法 HTML 仍能由旧 renderer 展示。

## Open Questions

无阻塞提案的问题。实施前验证选定 Plyr 版本的许可证、全屏配置和样式产物；如果要求真正阻止 F12 获取媒体，须另立服务端保护方案，不能将当前交互开关验收为这种能力。
