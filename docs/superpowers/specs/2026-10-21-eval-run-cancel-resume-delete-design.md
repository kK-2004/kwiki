# RAG 评测 run 的中断 / 继续 / 删除（Redis 持久化）设计

日期：2026-10-21　分支：`feat/langfuse-rag-eval`　前置设计：`2026-10-20-langfuse-rag-eval-design.md`

## 1. 目标

- 管理端可**中断**进行中的 run：在途题立即取消（基线检索与完整工作流都被中断），已完成题保留，在途题不计入结果。
- 对已中断（或失败）的 run **继续**：只跑未完成的题，沿用同一 runId / runName（Langfuse 上仍是同一个 dataset run），最终汇总包含全部题。
- **删除**已结束的 run：清理持久化数据；可选同时删除 Langfuse 上的 trace 与 dataset run。
- run 状态与逐题结果持久化到 **Redis**，应用重启后可列出历史、继续未完成的 run；**不再写本地报告文件**，报告改为管理端按需下载 JSON。

不加数据库表（不涉及 Flyway）：评测结果是可重跑的派生数据，Redis 足够，且无需关系查询。

## 2. 存储：`EvalRunStore`

接口（`com.kwiki.eval`）：

| 方法 | 说明 |
|---|---|
| `create(RunMeta)` | 新建 run：写 meta、state=RUNNING、加入历史索引 |
| `meta(runId)` / `list(limit)` | 读单个 / 按创建时间倒序列出 |
| `saveCase(runId, CaseResult)` | 逐题检查点（以 caseId 幂等覆盖） |
| `cases(runId)` | caseId → CaseResult |
| `update(runId, RunUpdate)` | 按字段更新 state / summary / error / reportPartial / heartbeatAt |
| `requestCancel(runId)` / `cancelRequested(runId)` | 跨实例取消标志 |
| `delete(runId)` | 删除全部键与索引 |

Redis 实现（`kk.common.redis.enabled=true` 时，`StringRedisTemplate` + Jackson JSON，不设 TTL）：

- `kwiki:eval:runs` — ZSET，member=runId，score=创建时间毫秒，用于历史列表。
- `kwiki:eval:run:{runId}` — HASH：`meta`（runId、runName、dataset、kbId、notes、localOnly、total、config、createdAt）、
  `state`、`heartbeatAt`、`cancelRequested`、`summary`、`error`、`reportPartial`。按字段 HSET，避免并发整体覆盖。
- `kwiki:eval:run:{runId}:cases` — HASH：caseId → CaseResult JSON。

未启用 Redis 时使用内存实现（语义相同，重启丢失），由 `EvalRunStoreConfiguration` 依据 `RedisConnectionFactory` 是否存在选择。

`EvalReportWriter` 与 `kwiki.eval.report-dir` 删除；`RunStatus.reportPath` 删除。
`GET /runs/{runId}/report` 从存储组装与原本地报告同结构的 JSON：`runName`、`config`、`summary`、`cases`（按数据集顺序）。

## 3. 执行模型

- 每次执行（start 或 resume）在单线程 runner 上进行，仍受进程内 `busy` 标志与 Redis 锁 `eval-run` 双重互斥。
- 每次执行新建题目线程池（`kwiki.eval.concurrency`），runner 用 `ExecutorCompletionService` 收集结果，以 200ms 为周期轮询取消标志。
- **由 runner 线程统一**写检查点 → 递增 completed → `recordTrace`。工作线程只计算 `CaseResult`，被中断不影响其他题的上报。
- 心跳：执行期间每 5 秒写 `heartbeatAt`，并读取 `cancelRequested`（其他实例收到的中断请求）。

### 中断

`POST /runs/{runId}/cancel`：
- 本实例持有该 run：置取消标志并 `shutdownNow()` 题目池（中断工作线程）。`WorkflowProbe` 中 `block()` 被中断时 Reactor 会 dispose 订阅，
  `LangGraphAgenticWorkflow` 的 `sink.onCancel` 随即关闭 `RunContext`、中断 worker；基线检索被中断后抛出异常，结果被丢弃。
- runner 立即停止等待在途题，flush 已缓冲的 trace（失败记 reportPartial），状态置 `CANCELLED`，释放互斥。
- 本实例不持有：写 `cancelRequested=true`，持有实例在下次心跳（≤5s）时执行同样的取消。
- 非 RUNNING 的 run 返回 409 `eval-run-not-running`。

### 失联判定

状态为 RUNNING、本实例不持有、且 `heartbeatAt` 超过 2 分钟未更新（应用重启或实例失联）的 run，读取时视为 `CANCELLED`，
error 为 `interrupted`，可继续或删除。

### 继续

`POST /runs/{runId}/resume`（仅 `CANCELLED` / `FAILED`，否则 409 `eval-run-not-resumable`）：
- 沿用 meta 中的 dataset、kbId、notes、localOnly、runName 与配置快照；由当前管理员身份执行；同样检查语料就绪与 Langfuse 可用性。
- 待跑题 = 当前数据集选出的题 − 已有检查点的题（含失败题，失败题已作为结果记录，不重跑）。
- 全部完成后对检查点中**属于当前选题**的结果重新汇总；traceId 由 `runName:caseId` 确定性生成，Langfuse 不会重复。
- 中断时在途的题没有检查点，因此会重新召回、重新生成。

### 删除

`DELETE /runs/{runId}?langfuse=true|false`（RUNNING 返回 409 `eval-run-running`）：
- 先删存储。
- `langfuse=true` 且该 run 非 localOnly 且 Langfuse 已配置：按检查点题目计算 traceId，每批 ≤100 调 `DELETE /api/public/traces`，
  再调 `DELETE /api/public/datasets/{dataset}/runs/{runName}`（404 视为已删除）。Langfuse 删除是异步、不可恢复的。
- Langfuse 删除失败不回滚本地删除，响应 `{ "langfuseDeleted": false, "langfuseError": "..." }`，界面提示到 Langfuse 控制台手动清理。

## 4. 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/admin/eval/runs` | 最近 50 个 run 的 `RunStatus`（倒序） |
| GET | `/api/v1/admin/eval/runs/{runId}` | 单个 run 状态（含 summary） |
| GET | `/api/v1/admin/eval/runs/{runId}/report` | 完整报告 JSON（下载） |
| POST | `/api/v1/admin/eval/runs/{runId}/cancel` | 中断 |
| POST | `/api/v1/admin/eval/runs/{runId}/resume` | 继续 |
| DELETE | `/api/v1/admin/eval/runs/{runId}?langfuse=` | 删除 |

`RunStatus`：`runId, runName, dataset, state, total, completed, summary, error, localOnly, reportPartial, createdAt`；
状态 `RUNNING | COMPLETED | REPORT_PARTIAL | CANCELLED | FAILED`。

## 5. 管理端

- 新增「历史评测」表格：名称、数据集、状态、进度、创建时间；操作：查看、中断（RUNNING）、继续（CANCELLED/FAILED）、
  下载 JSON（有已完成题时）、删除（非 RUNNING）。
- 中断、继续使用确认弹窗；删除弹窗带「同时删除 Langfuse 上的 trace 与 dataset run」复选框（默认勾选，localOnly 的 run 禁用）。
- 当前 run 卡片同样提供中断/继续/下载按钮；中断后停止轮询，继续后恢复轮询。

## 6. 测试

- 后端：取消在途题（fake `AgenticWorkflowPort` 返回 `Flux.never().doOnCancel(...)`，断言订阅被取消、该题不入检查点、状态 CANCELLED）；
  继续只跑未完成题且汇总含全部题；删除清理存储并按 traceId 调用 Langfuse 删除；失联 run 视为 CANCELLED；
  Redis 存储与 CaseResult JSON 往返；Langfuse 删除接口请求格式与 404 处理；控制器路由。
- 前端：vitest 覆盖历史列表渲染、中断/继续/删除弹窗与 API 调用、下载。
