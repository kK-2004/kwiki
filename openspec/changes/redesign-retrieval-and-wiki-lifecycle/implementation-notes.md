# 实施基线笔记（任务 1.1 / 1.3 / 1.4 / 1.5）

## 1.1 工作区与替换点

工作区在本次实施开始时为 clean（main @ 6fe3e75）。未归档变更中与本提案冲突的要求按 proposal 覆盖关系处理：

- `refactor-llm-and-agentic-orchestration`：任意策略 planner、跨轮 RRF 累计、证据 QA 后生成、16 次模型调用预算 → **被本变更替换**（planner/工具适配保留用于非主链兼容调用）。
- `migrate-agentic-rag-and-build-wiki`：自动父展开、无回收站归档 → **被本变更替换**。

现有行为替换点（文件 → 新行为）：

| 现有行为 | 位置 | 新行为 |
| --- | --- | --- |
| planner 任意策略 → retrieve → 证据 QA → 生成 | `LangGraphAgenticWorkflow` 节点 plan/validate/retrieve/quality/generate | 确定状态机：基础子→基础父→扩大子→扩大父→改写；候选先 QA 再发布 |
| 跨轮排名累计（Accumulation.rankings putIfAbsent） | `HybridRetrievalOrchestrator` | 主问答链路每次检索独立 RRF；仅 embedding 在 run 内复用 |
| RRF 后立即父回取 | `HybridRetrievalOrchestrator.retrieve` | 子/父拆分（`QaChildRetrievalService` + 独立父回取），首个 QA 前不读父正文 |
| `quality-v1` 仅评估证据 | `QualityAnalyzerPort` / `QualityAnalyzerAdapter` | `quality-v2` 输入含候选回答；0.80 三维门控 |
| 改写仅复用 QA suggestedQueries | `LangGraphAgenticWorkflow.rewrite` | Query Rewrite Agent 结构化反馈调用（原始 query/全部历史/拒绝原因/TopK 事实） |
| 16 模型/9 工具/64 步/180s | `AgenticLimits` | 32 模型/12 工具/128 步/300s + 3 query 轮/2 改写 |
| 页面归档=改状态+排队删除 | `PageRevisionService.archive` | 统一 `ResourceArchiveService`：批次+子树+同步 ES 删除+回收站 |
| KB 归档仅改状态 | `KnowledgeBaseService.archive` | 同上，覆盖全部有效页面/附件/源资源 |
| 附件默认全部入索引 | `AttachmentService.upload` → `enqueueAttachmentUpsert` | 仅图片附件入索引；其余仅展示 |
| textarea 编辑器 | `MarkdownEditorAdapter.vue` | 源码区间映射的媒体块编辑视图 + 源码模式 |
| 手写转义渲染（图片落普通链接） | `frontend/src/features/wiki/components/render.ts` | 支持标准图片 + 受限 img/audio/video |
| 单弹窗归档确认 / 无确认 | `KnowledgeBaseSettings.vue` / `WorkspacePage.vue` | 两个连续确认弹窗 + 幂等提交 |
| started/route/rewrite 原始状态展示 | `ConversationMessages.vue` / `AgenticAnswerPanel.vue` | 共用 `RetrievalActivity` 折叠时间线 |

## 1.3 归档/恢复/物理清理依赖清单（表级）

按物理清理顺序（先删引用、后删主体）。FK 与无 FK 逻辑引用均核对自 V1–V16 migration：

1. `selection_anchor`（page_id、revision_id）
2. `wiki_comment`（page_id、anchor_id；子评论 parent_id/reply_to）→ 软删除已由每日任务处理，物理清理删除剩余行
3. `comment_like`、`page_like`、`page_favorite`（comment_id/page_id）
4. `comment_mention`、`notification`（page_id/comment_id/anchor_id）
5. `wiki_page_member`、`wiki_page_audience_member`（page_id）
6. `recent_visit`、`page_summary`、`stats_revision`/`stats_repair`（page_id）
7. `wiki_link`（from_page_id/to_page_id）
8. `wiki_page_tag`（page_id）；孤立 `wiki_tag` 不删除（可能被其他页面共享）
9. `source_document`（page_id、attachment_id）——附件与页面双向
10. `wiki_page_revision`（page_id）→ `page_revision_media`（revision_id、attachment_id）
11. `wiki_import_job`（kb_id/attachment_id 引用置空或删除行）
12. `attachment`（kb_id）：仅当**独占**归属（无任何有效 source_document/页面媒体引用）才物理删除元数据；共享附件保留。内容中心文件不调用删除（SDK 0.1.3 无 delete）。
13. `indexing_job`（resource 相关行清理）
14. `wiki_page`（本体，最后删）；KB 级：`knowledge_base_member`、`resource_invitation`/`resource_join_request`、`ownership_transfer`、`knowledge_base`
15. `chat_message`/`chat_run`：**保留**（会话快照），归档来源的引用标记不可用即可。
16. `archive_batch_item` 逐项标记 purged；全部完成后删 `archive_batch`。

授权注意：`ResourceAuthorizationService` SQL 要求 `status='ACTIVE'`，回收站查询/恢复**不得**复用该加载器，改用批次+管理动作检查。

## 1.4 内容中心 SDK 能力核验（离线）

SDK 0.1.3（`com.kk.sdk.ContentCenterClient`，javap 核验）：

- `upload(InputStream, name, size, UploadOptions.contentType)` → `UploadResult{fileId, size, contentType, storageKey}`：**服务端返回已校验的 contentType/size**，可用于「实际内容/MIME 校验」的服务端交叉验证（与魔数校验叠加）。
- `getDownloadLink(fileId, filename, expiresIn)` → `{url, expiresIn}`：短期签名 URL，可反复刷新 → 满足「短链刷新、不把签名 URL 写回正文」。
- `getCdnLink` → `{url, expiresIn, permanent, contentType}`：永久 CDN 链接（permanent=true 时），Content-Type 由内容中心维护。
- **无 delete 接口**：物理清理只删本地行，远端文件生命周期归内容中心（与设计一致）。
- Range/跨域：取决于签名 URL 指向的对象存储（通常 S3 兼容，支持 Range 与 CORS 由桶配置决定）。**离线环境无法实测**；实现上播放器按标准 `<audio>/<video controls preload=metadata>` 使用，加载失败显示可读占位并提示错误分类。该项缺口记录在验收记录（11.3 由可丢弃环境实测补充）。

媒体允许类型（浏览器能力为准，不做转码）：

- 图片：`image/png`、`image/jpeg`、`image/gif`、`image/webp`（魔数校验）
- 音频：`audio/mpeg`(mp3)、`audio/wav`、`audio/ogg`
- 视频：`video/mp4`、`video/webm`
- 大小默认沿用 50 MiB（`kwiki.attachments.max-bytes`），媒体与文档一致。
- 不支持格式反馈：上传前 MIME 白名单拒绝 → 400「不支持的媒体类型」；内容魔数与声明不符 → 400「文件内容与类型不符」。

## 1.5 Markdown 解析与媒体块编辑方案

- **后端**：沿用 `commonmark`（已锁定，`CommonMarkMarkdownPort`）做安全渲染；媒体引用提取用独立扫描器（代码围栏/转义感知），不叠加正则做完整语法。
- **前端编辑器**：不引入完整 WYSIWYG 依赖。实现**源码区间扫描器**（`mediaBlocks.ts`）：识别标准 `![alt](url)`、受限 `<img>/<audio>/<video>` 媒体 token 并返回 `start/end` 源码区间；扫描器跳过围栏代码块与转义字符。编辑视图 = 文本段（textarea，原生输入/撤销）+ 媒体节点（真实预览+控件）交替，编辑只改写对应区间，未触碰文本逐字节保真（round-trip fixtures 固定）。复杂源码可切纯源码模式。
- **前端渲染**：`render.ts` 升级为基于行/inline token 的渲染（图片、受限媒体标签白名单属性、代码隔离、转义），保持零新运行时依赖。
- Round-trip 验证：`frontend/tests/markdown.spec.ts` 增加复杂源码样例（嵌套括号、转义、代码块中的媒体文字、未闭合链接）。
