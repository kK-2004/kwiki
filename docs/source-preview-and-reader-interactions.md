# 来源预览、引用聚焦与流式跟随——行为与安全边界

本文档描述 `enhance-wiki-document-and-chat-experience` 变更引入的阅读器与
对话交互契约，供工作区验收与 API 使用方参考。

## 1. 来源文件预览（PDF/DOCX）

### 可见性规则

- 页面读取响应 `GET /api/v1/knowledge-bases/{kbId}/pages/{pageId}` 含可选
  `sourceDocument` 摘要：`{ format: "PDF" | "DOCX", fileName, byteSize, attachmentUuid }`。
  字段仅在页面派生自可预览来源附件（同库、`STORED`、声明类型为 PDF/DOCX）
  时返回；Markdown 导入页与普通页面不返回该字段，前端不显示任何页签。
- 阅读器仅在摘要存在时显示“`<格式>` 源文件 / 解析文本”页签；默认停留在
  解析文本，切换不重建阅读容器（保留阅读位置），切离源文件页签即销毁
  预览资源（Blob URL、渲染任务、观察器）。

### 授权与内容获取

- PDF 预览字节来自页面作用域同源端点
  `GET /api/v1/knowledge-bases/{kbId}/pages/{pageId}/source-preview/content`。
  前端通过 fetch 读取字节并交给 PDF.js，不使用 iframe 导航文件地址，避免触发下载。
  DOCX 继续通过 `/source-preview` 获取短期地址。
  服务端在每次请求时重新校验：页面读取授权（`ResourceAuthorizationService`）、
  页面 `ACTIVE` 且属于路径知识库、`source_document(page, attachment)` 关联、
  附件 `STORED` 且 content-center file id 有效、大小不超过
  `kwiki.source-preview.max-bytes`（默认 20 MB，与导入上限一致）、
  字节签名（`%PDF-` / OOXML `[Content_Types].xml`）与声明 MIME 一致，
  并在返回字节前做最终授权复核。
- 拒绝语义：关联/状态不符返回 404（与“不存在”不可区分）；签名不符或超限
  返回 400；失权返回 403。响应携带 `Cache-Control: private, no-store`，
  永远不返回、也不在前端持久化任何永久公开（CDN）地址。
- 前端每次进入源文件页签重新取回 Blob 并按需懒渲染：PDF.js 逐页
  IntersectionObserver 渲染（首屏 2 页急切渲染，单画布 ≤ 8M 像素，最多
  500 页）；DOCX 由浏览器端 docx-preview 适配器渲染。两个适配器独立分包，
  不进入阅读器首包。

### 水印

- 预览容器叠加不可交互（`pointer-events: none`、`aria-hidden`）的重复水印，
  内容为“当前用户 displayName/username + 查看时间”，覆盖滚动视口与打印
  样式，滚动、缩放、翻页、窗口尺寸变化均保持可见。
- 边界声明：水印仅承担泄漏溯源与视觉提醒，不是 DRM——无法阻止截图、
  拍屏或底层网络访问；真正的边界是服务端授权与 `no-store`。

## 2. 引用聚焦（参考列表 → 正文）

- 深链格式不变：`#/knowledge-bases/{kbId}/{pageId}?chunk={childChunkKey}`。
  定位以 (chunkKey, 页面, 页Id) 为键的一次性事务执行：目标改变或组件
  卸载使旧任务失效；迟到响应不能覆盖新页面的定位结果。
- 正文就绪握手：等待阅读器自有容器引用提交（含异步媒体挂载的两个 tick）
  后再定位；`?chunk` 参数只在最终成功或最终错误状态后消费。
- 匹配规则：规范化空白后寻找 excerpt 全部命中；唯一命中即定位；多命中时
  取距 `charStart` 最近者，且距离不超过 2000 字符（不可验证即拒绝）；
  无命中或不可验证时不高亮任何文本，仅显示状态与引用摘要。
- 可见反馈：优先 CSS Custom Highlight API（`::highlight(kwiki-citation)`）；
  浏览器不支持时用临时 `<mark data-kwiki-citation>` 范围包裹，超时
  （2600ms）、再次定位、目标改变或卸载时无损清理。失效引用（404/403）
  显示“原片段已失效或无权访问”，不泄露页面标题或片段内容。

## 3. 浮窗会话导航

- 展开的浮窗提供“新建会话”“历史会话”：历史列表复用全局 conversation
  store 的 `sessions`/`loadSessions`（加载、空、错误可重试），选择会话
  调用与完整页相同的 `select(id)`，就地回到消息视图并续聊到原 sessionId；
  展开完整页携带相同 sessionId，不重复获取或重放 turn。
- 生成进行中：历史列表禁用切换并解释原因，绝不隐式取消、复制或重新
  订阅当前 run（单一 SSE 归 store 所有）。
- Escape 顺序：先关闭历史面板；面板已关闭时才最小化浮窗。列表在浮窗
  边界内滚动，打开面板时焦点保持在浮窗内。

## 4. 流式跟随（follow-tail）与思考动画

- 每个可滚动区域（消息区、展开的思考区）独立维护 `FOLLOWING/PAUSED`
  状态，底部阈值 48px：用户上滚离底即暂停（token、检索步骤、引用、
  历史、媒体等任何高度变化都不改写 scrollTop）；回到阈值内立即恢复。
- 程序滚动帧级合并（同一帧多次内容变化只写一次 scrollTop），程序写入
  不改变用户意图判定；嵌套滚动区互不影响（思考区内滚上不暂停消息区）。
- 会话初始化/切换后消息区从底部开始并处于跟随状态。
- 思考摘要展开/折叠使用高度+透明度过渡（grid-rows，220ms），动画期间
  保留内容、滚动位置、跟随状态与 `aria-expanded`/region 关系；
  `prefers-reduced-motion: reduce` 时过渡立即完成（`--thinking-duration: 0ms`），
  内容与语义不变。

## 5. 验证入口

- 组件/单元：`frontend/tests/citation-focus.spec.ts`、`source-preview.spec.ts`、
  `floating-history.spec.ts`、`follow-tail.spec.ts`（`npm test`）。
- 后端：`PageSourcePreviewServiceTest`（`mvn test`）。
- 浏览器端到端（构建产物 + 路由级 API 仿真）：`npm run e2e`
  （Playwright/Chromium；`e2e/reader-and-chat.spec.ts`、`e2e/fixtures.ts`、
  `e2e/sample.docx`）。
- 手动补充项（自动化未覆盖）：Safari/Firefox 实机、超长真实 PDF（>100 页）
  与开启多模态的真实 DOCX、以及低带宽下的预览中断恢复。
