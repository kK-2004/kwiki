# 验收记录（任务 11.1–11.5）

实施日期：2026-09-08/09。2026-09-09 已按用户要求恢复浏览器联调；本记录包含当前已完成证据与剩余阻塞项。

## 11.1 单元/契约/离线验证

| 验证 | 命令 | 结果 |
| --- | --- | --- |
| 后端完整测试 | `./mvnw test` | **376 tests，10 failures，0 errors，26 skipped**。失败清单与干净基线（HEAD 6fe3e75，经 git worktree 独立验证）**完全一致**（SecurityIntegrationTest×3、CommonResponseContractTest×5、TracePropagationIntegrationTest×1、DependencyReadinessIntegrationTest×1，均为离线环境下预先存在的 JWT/上下文类失败），**本次变更零新增失败**；新增/重写测试（AgenticWorkflowTest×12、ResourceArchiveServiceTest×7、QaRetrievalContractsTest×6、JdbcIndexingJobEnqueuerTest fenced 用例、AttachmentServiceTest 媒体用例等）全部通过 |
| Flyway 只读校验 | `scripts/validate-migrations.sh` | 20 个 migration（V1–V20）命名/唯一版本校验通过；未修改任何历史 migration |
| 前端 | `pnpm typecheck` / `pnpm test` / `pnpm build` | 全部通过（33 个前端测试，含新增媒体渲染/round-trip 用例） |

## 11.2 MySQL/ES 一次性环境验收（离线替代说明）

本机为离线开发环境（无 Docker 中间件，见 `scripts/check-no-docker-middleware.sh`），双路真实排名/QA 全链/归档即时消失/恢复重建/过期清理的**隔离环境实测待可丢弃 MySQL/ES 环境执行**。已完成的离线等价验证：

- 双路真实排名 + RRF 数值：`QaRetrievalContractsTest`（1/65+1/62 精确值、扩检重算、TopK 裁剪、单路降级）。
- QA 完整恢复链：`AgenticWorkflowTest`（四阶段顺序、即停、3 轮 2 改写预算、精确拒答、qa-unavailable 终态、候选不泄漏、取消无后续调用）。
- 归档即时隔离：`RetrievalLifecycleService` fail-closed 测试 + `EsScopeFilterBuilder` 归档排除（含 superuser）+ `AuthorizationScopeResolver` 归档 KB 排除；`IndexingWorkerTest` 延迟 upsert/delete fencing。
- 过期批量物理清理：`ResourceArchiveServiceTest`（保留窗、幂等、410/409、根目录重定位、仅已发布重建）+ 清理服务的固定 cutoff/行锁/依赖序实现。

## 11.3 真实内容中心媒体验收

离线环境无真实内容中心。SDK 能力面（javap 核验 0.1.3）：`upload` 返回服务端校验的 contentType/size；`getDownloadLink` 返回短时效 URL；`getCdnLink` 含 contentType；**无 delete 接口**（物理清理仅本地行）。魔数校验（`MediaContentSniffer`）与 SDK 返回类型交叉验证已实现并测试；PNG 上传/预览/不一致拒绝见 `AttachmentServiceTest`。**Range/跨域实测与三类媒体全链路验收待可丢弃环境执行**，能力缺口按 design §Open Questions 记录（不伪报通过）。

## 11.4 / 15.2 浏览器对照原型验收

已登录浏览器实测通过：

- 全局导航已移除共享空间和全局搜索；聊天浮窗仅保留最小化、最大化。
- 会话知识范围弹出三列选择器，可选择知识库下 Wiki，右列按知识库默认折叠并显示已选/总数；范围请求已被后端接收并进入真实检索流程。
- 编辑器只显示源码/预览；源码在原位置渲染内部图片，短链返回后图片自然尺寸为 1530×324，阅读页和编辑页均正常显示且不越过正文容器。
- 点击图片后布局/尺寸控件保持可操作；居中、50% 即时生效。
- 代码块菜单展示语言列表；JavaScript 预览输出 `hljs language-javascript`。
- 历史列表点击「查看版本」只打开只读内容弹窗，旧版本才显示「恢复到当前版本」，查看未创建版本。
- 草稿实测暴露旧模型会增加修订；已改为 V20 独立可覆盖草稿表 + 正式修订发布时间标记。V20 为保留数据迁移，不删除旧快照；IntelliJ 本地进程已使用原配置启动并成功应用 V20，Flyway 日志确认数据库已升级至 version 20。

第三轮反馈的前端回归已补充：编辑器空白区接管焦点，媒体-only 文档保留插入点；外链/受控媒体显示加载、成功、失败状态，失败和 10 秒超时可见且可删除；预览和阅读的受控图片显示加载卡片；新增图片默认居中，自动宽度按 50% 渲染。2026-09-09 在 `http://localhost:5173` 已登录会话复测：阅读页先显示「正在加载媒体…」后显示图片；源码插入 `https://example.invalid/expired.png` 先显示加载卡，约 1 秒后显示「媒体加载失败或已超时」，选中卡片点击「删除媒体」后源码恢复。点击编辑器底部空白坐标后活动元素为最后一个正文 textarea。

第四轮反馈修复短链返回后的二次跳动：短链解析前后的卡片保持同一最小高度；下载/解码期间图片绝对定位、隐藏且不参与布局，`img.decode()` 完成后才切换为 ready。新增延迟 decode 回归证明 promise 未完成时 loading 卡不会提前消失。前端现有 36 项测试全部通过。

同轮补充多行正文首次渲染验收：textarea 在 mounted/updated 阶段按实际 `scrollHeight` 自动增高。已登录浏览器未输入任何字符时，9 行正文的 `styleHeight/clientHeight/scrollHeight` 均为 243px，单行段落保持 31px；新增挂载期自动增高回归后，前端现有 37 项测试全部通过。

浏览器仍需在当前登录会话中补做：重复保存草稿不增加版本、发布说明窗口/空说明生成、普通附件真实上传下载、归档两次确认与回收站、范围检索最终回答。后端本地进程已带原配置运行；ES/内容中心不可用时，检索和索引相关联调会按失败状态展示。

## 11.5 配置/API/SSE/日志与部署说明

### 新增/变更配置键（全部有默认值，无需必填 yml 变更）

- `kwiki.agentic.max-query-rounds:3`、`max-rewrites:2`、`max-model-calls:32`、`max-tool-calls:12`、`max-steps:128`、`timeout:300s`、`model-deadline:30s`、`max-hybrid-retrievals:6`、`max-parent-fetches:6`、`max-generations:12`、`max-quality-reviews:12`、`qa-threshold:0.80`、`candidate-max-chars:32000`
- `kwiki.retrieval.qa.*`（base-branch-topk 20 / base-final-topk 8 / expanded 50/20 / base-parent-limit 8 / expanded 16 / base-child-chars 12000 / expanded 24000 / parent-chars 24000）、`lifecycle-filter-ttl:5s`
- `kwiki.archive.index-retry-interval:30s`、`cleanup-batch-size:200`
- `kwiki.attachments.allowed-content-types` 默认集新增图片/音频/视频类型（含魔数校验）
- `kwiki.indexing.legacy-media-cleanup-enabled:true`、`kwiki.export.total-bytes-limit:200MB`

### API/事件面

- 新增：`GET /api/v1/trash`、`POST /api/v1/trash/{batchId}/restore`（409/410 语义）；`GET .../attachments/{uuid}/media-preview-url`；`POST .../pages/{pageId}/export`（md/html/ZIP）；`GET /api/v1/chat/sessions/{id}/runs/{requestId}/events`（活动重放）。
- SSE：新增 `activity` 版本化事件（schemaVersion/stepId/queryRound/attemptStage/phase/status/metrics/reasonCode）；token/citations/done/error 协议不变；旧持久事件兼容映射。
- 归档旧入口（页面/树/知识库）保留并委托 `ResourceArchiveService`，返回体新增 `indexSyncStatus`（ES 故障时 202 语义由 PENDING 表达，前端展示「检索已停用，索引清理重试中」）。

### 部署/回退

1. 顺序：先部署（V17/V18 自动迁移 + 归档权威过滤 + fencing/outbox + 同步删除），再启用回收站恢复 UI 与每日清理（随应用自动生效，cron Asia/Shanghai 01:00，MySQL 咨询锁互斥）。
2. V17 对历史 ARCHIVED 回填：archivedAt=迁移时刻、purgeAfter=+168h、origin=LEGACY_BACKFILL（不用 updatedAt 猜测）。
3. 上线后自动执行：非图片附件历史 chunks 清理（幂等 DELETE 队列）；PENDING 批次索引删除重试（30s 周期）。
4. 回退：可回退到「保留新增表/列 + 逻辑归档过滤」的应用版本；**禁止回退到可检索已归档内容的旧版本**；已物理清理数据需按备份策略恢复。
5. 前端：新路由 `/trash`、`/knowledge-bases/{kbId}/trash`；旧会话消息无活动事件时安全降级显示。

### 与旧未归档规格的协调清单（归档时合并主规格用）

- `refactor-llm-and-agentic-orchestration`：16 模型调用/64 步/180s 预算、planner 任意策略主链、证据 QA 后生成 → 以本变更 32/128/300s、确定状态机、候选 QA v2 为准；planner/工具适配保留为非主链能力。
- `migrate-agentic-rag-and-build-wiki`：归档仅改状态、无回收站 → 以本变更批次回收站为准；附件全量入索引 → 以「仅图片入索引」为准。
- 其余授权、引用、SDK、持久会话约束在两变更中一致，继续适用。
