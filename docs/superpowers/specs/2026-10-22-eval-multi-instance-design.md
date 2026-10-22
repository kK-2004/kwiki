# RAG 评测多实例执行设计

日期：2026-10-22
状态：待评审
前置：`docs/superpowers/specs/2026-10-20-langfuse-rag-eval-design.md`（评测本体）、评测中断/继续/删除（run 状态与逐题检查点已存 Redis）

## 1. 目标与约束

- 每个应用实例同时最多执行 **1** 个评测 run；集群同时执行的 run 总数不超过 `kwiki.eval.max-concurrent-runs`（默认 **2**），超出的 run 排队。
- 管理端请求经负载均衡落到任意实例：开始、继续、中断、删除、查询、下载都必须与「run 在哪个实例执行」无关。
- 同一个 run 任何时刻只能由一个实例执行；执行实例宕机或长时间停顿后被接管时，旧实例不得再写入。
- Langfuse 上的 dataset run 最终完整：执行实例宕机不丢上报。
- 未启用 Redis 时退化为单实例，走同一套代码（内存实现）。

不在本期：多实例下 Langfuse 与模型 provider 的全局限流（见 §9）。

## 2. 现状（改造前）

| 能力 | 现状 |
|---|---|
| run 状态、逐题检查点 | `RedisEvalRunStore`（`kwiki:eval:runs` ZSET、`kwiki:eval:run:{runId}` HASH、`…:cases` HASH） |
| 本实例互斥 | `EvalRunService.busy`（`AtomicBoolean`） |
| 集群互斥 | 执行时获取全局锁 `eval-run`（`KwikiDistributedLocks`），全集群只能跑 1 个；拿不到锁时 run 记为 `FAILED: eval-busy` |
| 跨实例中断 | 写 `cancelRequested`，执行实例每 5 秒心跳时读取 |
| 宕机识别 | `RUNNING` 且心跳超过 `STALE_AFTER`（2 分钟）、本实例未执行 → 视为已中断 |
| Langfuse 上报 | `LangfuseRestClient` 进程级共享缓冲，满 20 条或 `flush()` 时发送；`flush()` 只报告失败条数 |

## 3. 状态机

新增 `QUEUED`：

```
提交 ─▶ QUEUED ─认领─▶ RUNNING ─▶ COMPLETED / REPORT_PARTIAL / FAILED
          │                 │
          └──中断──▶ CANCELLED ◀──中断 / 心跳过期──┘
CANCELLED / FAILED ─继续（重新提交）─▶ QUEUED
```

`EvalRunState` 增加 `QUEUED`。心跳过期的 `RUNNING` 仍按现有规则对外显示为 `CANCELLED`（错误码 `interrupted`）。

## 4. 提交（开始 / 继续）

收到请求的实例只做校验与入队，立即返回：

1. 校验：数据集存在、`kbId` 必填、未勾选「仅本地」时 Langfuse 已配置、评测知识库索引就绪（与现有 `prepareNew` 相同）；继续时 run 必须为 `CANCELLED` / `FAILED` / 心跳过期。
2. `RunMeta` 新增 `submittedBy`（用户 id）；继续时更新为本次操作的管理员。
3. 以 Lua 脚本原子地把状态置为 `QUEUED`（继续时校验旧状态，并发重复点击「继续」只有一个成功，其余返回 409 `eval-not-resumable`），记录 `queuedAt`。
4. `XADD kwiki:eval:queue * runId {runId}`。
5. 返回 run 状态（`QUEUED`）。

开始与继续不再返回 `eval-busy`：没有空闲名额时排队。

## 5. 认领与执行（每实例一个认领线程）

`EvalWorker` 每 2 秒尝试一次认领（`XREADGROUP` 阻塞 2 秒，`COUNT 1`），按序获取，任一步失败则释放已拿到的名额：

1. **本实例名额**：进程内 `Semaphore(1).tryAcquire()`，没有则本轮不认领。
2. **集群名额**：`kwiki:eval:slots` ZSET（member = 实例标识，每实例至多占 1 个；score = 到期毫秒数）。Lua 脚本：`ZREMRANGEBYSCORE` 清除已过期 → `ZCARD < max` 时 `ZADD` 占位（到期 = now + 60 秒）→ 返回成功。执行期间心跳续期，结束时 `ZREM`。实例宕机后占位到期自动释放。
3. **读取任务**：消费组 `kwiki-eval-workers`，消费者名 = 实例标识。读不到则释放 1、2。
4. **检查**：run 记录不存在（已删除）或已 `cancelRequested` / 非 `QUEUED` → `XACK` 后跳过（已取消的置 `CANCELLED`）。
5. **提交人校验**：按 `submittedBy` 重新加载用户，仍为启用的管理员才继续，否则置 `FAILED: submitter-not-authorized`。检索作用域用该用户重新计算，不沿用提交时的权限。
6. **归属**：获取 run 锁 `eval-run:{runId}`（Redisson 看门狗续期）；Lua 脚本原子地 `HINCRBY ownerToken 1`、置 `RUNNING`、写 `ownerInstanceId`、刷新心跳，返回新令牌。
7. `XACK`，开始执行（沿用现有逐题执行、检查点与中断逻辑）。

**挂起任务回收**：读到但未 `XACK` 的消息（第 3~7 步之间实例宕机）空闲超过 60 秒后，认领线程用 `XAUTOCLAIM` 接管并按第 4 步起处理。

**实例标识**：`kwiki.instance-id`，默认 `{主机名}-{pid}-{4 位随机}`。

## 6. 写入令牌（防僵尸实例）

执行实例持有第 5 步得到的令牌。以下写入一律经 Lua 脚本比对 `ownerToken`，不一致或 run 已删除时不写入并返回失败：

- 逐题检查点（`saveCase`）
- 心跳（同时续期集群名额）
- 写终态（`finish`）
- 标记已上报（§8）

写入失败时执行实例记 INFO `eval run ownership lost runId={} instance={}`，取消在途题目并退出，不写任何终态。`EvalRunStore` 对应方法增加 `long ownerToken` 参数；内存实现同样比对。

## 7. 跨实例操作

| 操作 | 处理 |
|---|---|
| 查询 / 列表 / 下载 | 读 Redis。状态新增 `ownerInstanceId`；`QUEUED` 时返回 `queuePosition`（排在它之前、仍为 `QUEUED` 的 run 数，按 `queuedAt`） |
| 中断 | 写 `cancelRequested`；若为 `QUEUED`，Lua 原子地改为 `CANCELLED`（认领时跳过）；若为 `RUNNING`，`PUBLISH kwiki:eval:control {"runId":…,"action":"cancel"}`。所有实例订阅该频道，执行实例收到后立即取消在途题目；心跳轮询（5 秒）保留作兜底 |
| 删除 | `RUNNING` 且心跳新鲜 → 409 `eval-running`（先中断，与现状一致）；`QUEUED` → 先置 `CANCELLED` 再删除；执行实例下次写入因记录不存在而退出 |
| 继续 | 见 §4，任意实例可提交，由空闲实例执行 |
| 执行实例宕机 | 心跳过期后显示「已中断」；集群名额与 run 锁到期释放；可手动继续 |

## 8. Langfuse 上报不丢失

- 缓冲从 `LangfuseRestClient` 移到 `EvalRunService`，**按 run 管理**：每题完成后加入本 run 的缓冲，满 20 条或 run 结束、取消、失去归属时上报。
- `EvalReportPort` 以 `List<String> upload(List<TraceRecord> batch)` 取代 `recordTrace` / `flush`：同步发送一批（ingestion + dataset-run-items），返回上报成功的 trace id。`LangfuseRestClient` 不再持有缓冲；数据集同步只用 `upsertDataset` / `upsertItem`，不受影响。
- 逐题检查点增加 `reported` 标记；批量上报成功后经 §6 的令牌校验置为已上报。
- trace id 由 `runName + caseId` 确定性生成，重复上报只会覆盖同一条记录。
- 任何实例开始执行一个 run 时（含继续、接管），先把「有检查点但未上报」的题从检查点重建 trace 并补报，再继续未完成的题。
- 补报失败时与现有行为一致：标记 `reportPartial`，下次继续时再补。

结果：执行实例宕机后，Langfuse 上的数据在 run 被继续时补齐。

## 9. 不在本期：多实例全局限流

`LangfuseRestClient` 的 `kwiki.langfuse.max-requests-per-minute` 与模型 provider 的并发/QPS 限制均按实例计算。多实例部署时，需要按实例数调低每个实例的配额，或另行引入 Redis 全局令牌桶。运行说明中写明此点。

## 10. 无 Redis 时

队列、集群名额、控制通知、run 锁均提供内存实现（`InMemoryEvalWorkQueue` 等），与 `InMemoryEvalRunStore` 一起装配；行为退化为单实例排队执行。

## 11. 管理端

- 状态标签新增「排队中」，显示「前面还有 N 个」。
- 运行中显示执行实例标识。
- 「开始评测」不再因为已有评测在跑而报错，提示「已加入队列」。

## 12. 配置

| 键 | 默认 | 说明 |
|---|---|---|
| `kwiki.eval.max-concurrent-runs` | `2` | 集群同时执行的 run 上限 |
| `kwiki.instance-id` | `{主机名}-{pid}-{随机}` | 实例标识 |

均用 `@Value` 默认值，实际值放 Nacos，`application.yml` 不新增。

## 13. 测试

- 内存实现下的单元测试：提交即 `QUEUED`；本实例名额满时不认领；集群名额达到上限时排队、释放后认领；已取消的排队任务被跳过；提交人失去管理员权限时失败。
- 写入令牌：接管后旧令牌的检查点、心跳、终态写入被拒绝，旧执行静默退出。
- 补报：有检查点未上报的题在继续时补报；`upload` 部分成功时只标记成功的题。
- 中断：`QUEUED` 直接取消；`RUNNING` 经控制通知立即取消，通知丢失时心跳兜底。
- Redis 实现的 Lua 脚本（名额、状态 CAS、令牌校验写入）在提供 Redis 的集成测试中验证（沿用现有外部集成测试的环境变量约定，无 Redis 时跳过）。
