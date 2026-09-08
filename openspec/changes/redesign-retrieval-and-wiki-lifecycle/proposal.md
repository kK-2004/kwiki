## Why

当前问答过程暴露 `started / route / rewrite` 等内部状态，且实际链路提前展开父 chunks、只评估证据，无法实现用户要求的「子 chunks 预生成回答 → QA → 逐级补救」。Wiki 归档缺少可恢复的保留期和即时索引隔离，编辑器也缺少完整媒体编辑与导出能力。

## What Changes

- 参考用户提供的 HTML 原型，将问答活动改成默认折叠的一行摘要和可展开细时间线；对齐真实双路 ES 子 chunk 召回、RRF、TopK、预生成、QA、父 chunk 补充、扩大检索、查询改写事件，统一侧面板和会话页。
- **BREAKING**：知识问答采用确定顺序的门控状态机，替换原先由 planner 任意选择检索策略、先评估父证据再生成的行为。仅发布 QA 通过的候选原文；最多 3 轮查询（原始查询 + 2 次反馈改写），耗尽后输出「知识库中没有相关信息，我无法进行回答」。
- 每轮先使用关键词和向量两个独立子 chunk 排名，经 RRF 后取 TopK；不通过时依次补父 chunks、重新双路检索并扩大 TopK、最后交给 Query Rewrite Agent。改写输入包含原始问题、全部历史改写、拒绝原因和已扩大 TopK 的事实。
- Wiki 归档执行两次独立弹窗确认；Wiki 页面（含子树）和知识库进入保留 7 天的回收站，可恢复；立即隔离检索并同步删除对应 ES 子/父 chunks，保留可靠重试。
- 每天 Asia/Shanghai 01:00 分批物理清理归档超过 7 天的数据，输出 INFO 开始、批次、结束日志。
- 图片、音频、视频支持内容中心 SDK 上传或已有链接，编辑时在文中实时预览，悬停/聚焦媒体右下角可设置布局和尺寸；持久内容不保存临时签名链接。
- 附件仅图片进入文档索引；音频、视频、PDF、Office 等其他附件只在页面展示，不创建附件检索 chunks。同步拦截旧任务并清除非图片附件的历史索引。
- 工具栏新增图片、音频、视频下拉菜单、代码块、有序列表和 Markdown/HTML 导出。
- 增加覆盖 query、路由、双路检索、融合、生成、QA、补救与终态的关联 DEBUG 日志。

## Capabilities

### New Capabilities

- `qa-gated-retrieval-recovery`: 真实双路子 chunk 检索、候选回答 QA 和有界反馈补救。
- `retrieval-activity-timeline`: 两个问答入口共用的紧凑活动时间线、真实统计和历史重放。
- `wiki-recycle-bin`: 二次确认、7 天可恢复逻辑删除、即时检索隔离和每日清理。
- `wiki-inline-media-and-export`: 媒体上传/链接、文中编辑预览、布局尺寸、代码块/序号与导出。
- `rag-debug-tracing`: 贯通整个问答生命周期的结构化 DEBUG 诊断。

### Modified Capabilities

无已归档主规格可做 MODIFIED delta：当前 `openspec/specs/` 为空。以上能力使用 ADDED 规格描述本次增量；并非表示代码中没有既有功能。本变更覆盖未归档变更中相冲突的要求：`refactor-llm-and-agentic-orchestration` 的任意策略规划、累计多轮 RRF、证据 QA 后生成及 16 次模型调用预算；`migrate-agentic-rag-and-build-wiki` 的自动父展开和归档；其他授权、引用、SDK、持久会话约束继续适用。归档这些变更时须按本设计协调最终主规格。

## Impact

- 后端：`LangGraphAgenticWorkflow`、retrieval/rewrite/quality/answer ports 与 adapters、JSON schemas、SSE 和会话持久化；Wiki 页面/树/知识库归档、权限与缓存、ES 删除/索引 worker、附件和媒体服务、定时清理。
- 前端：`ConversationMessages.vue`、`AgenticAnswerPanel.vue`、SSE reducer/store、`PageEditor.vue`、`MarkdownEditorAdapter.vue`、共享 Markdown 渲染器、阅读器、归档入口和回收站路由。
- 数据：新增正向 Flyway migration 存储归档批次、过期时间、索引同步状态和媒体引用；实施时枚举已跟踪与未跟踪 migration 后分配版本，不修改历史 migration。
- 接口：扩展版本化活动事件，新增回收站查询/恢复和授权媒体预览接口，保留旧归档入口并委托统一服务；外部仍使用现有内容中心 SDK，不新增直连对象存储。
- 本次只提交 OpenSpec 设计文档；基于当前工作区（含其他变更的未提交实现）制定衔接方案。
