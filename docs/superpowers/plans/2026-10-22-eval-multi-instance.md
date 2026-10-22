# 评测多实例执行实现计划

> 本会话内联执行（TDD），设计见 `docs/superpowers/specs/2026-10-22-eval-multi-instance-design.md`。

**Goal:** 评测 run 提交即排队（QUEUED），各实例经认领线程领取执行；集群名额上限可配；跨实例中断/继续/删除与「run 在哪执行」解耦；写入令牌防僵尸实例；Langfuse 上报按 run 缓冲并在继续/接管时补报，执行实例宕机不丢数据。

**Architecture:** `EvalRunStore` 扩展 QUEUED 状态、submittedBy、排队索引（ZSET 按 queuedAt）、ownerToken 令牌校验写入与逐题 reported 标记；新增四个协调端口 `EvalWorkQueue`（Redis Stream + 消费组 + 挂起回收）/ `EvalClusterSlots`（ZSET + Lua 占位续期）/ `EvalControlBus`（Pub/Sub 即时中断）/ `EvalRunLocks`（run 锁），Redis 与内存双实现；`EvalRunService` 改为「提交只校验入队」+「按 run 缓冲上报、令牌写入」，`EvalWorker` 单线程认领后同步执行；`EvalReportPort.upload(batch)` 取代 `recordTrace`/`flush`。

**Tech Stack:** Spring Boot 3.5 / Java 21、Spring Data Redis 3.5（Stream、Lua `RedisTemplate.execute`、`RedisMessageListenerContainer`）、kk-common Redisson 锁、Vue 3 + Element Plus、vitest。

## Global Constraints

- 所有提交信息用简体中文，不加 Co-Authored-By 尾行。
- `application.yml` 不新增配置；`kwiki.eval.max-concurrent-runs`（默认 2）、`kwiki.instance-id`（默认空 → `{主机名}-{pid}-{4位随机}`）用 `@Value` 默认值。
- 无 Redis 时全部退化为内存实现，行为为单实例排队执行；不新增数据库表；不推送分支。
- 稳定错误码沿用现有命名：继续条件不满足仍为 `eval-run-not-resumable`（设计文中 `eval-not-resumable` 的落地名）。
- 认领第 4 步的「非 QUEUED 跳过」落地为：`QUEUED` 或心跳过期的 `RUNNING`（接管）继续执行；`cancelRequested` 置 `CANCELLED` 后跳过；记录不存在或其他状态跳过。
- 验证：`./mvnw -q test`；`cd admin-frontend && npm run typecheck && npx vitest run && npm run build`（构建类命令走 IntelliJ IDEA MCP）。

---

### Task 1: `EvalRunStore` 扩展：QUEUED、submittedBy、ownerToken、排队索引、reported

**Files:** Modify `eval/EvalRunState.java`, `eval/EvalRunStore.java`, `eval/InMemoryEvalRunStore.java`, `infrastructure/redis/RedisEvalRunStore.java`；Test `eval/InMemoryEvalRunStoreTest.java`（新增）, `infrastructure/redis/RedisEvalRunStoreTest.java`（扩展）。

**Produces:**
- `EvalRunState` + `QUEUED`；`RunMeta` + `submittedBy`；`RunRecord` + `ownerInstanceId`、`queuedAt`。
- `create` 状态置 `QUEUED` 并写 `queuedAt` / `submittedBy` / 排队索引；`markQueued(runId, submittedBy, now, staleAfterMillis)` Lua CAS（`CANCELLED`/`FAILED`/心跳过期 `RUNNING` → `QUEUED`，否则失败）。
- `cancelQueued(runId)`：`QUEUED` → `CANCELLED` 并清排队索引；`queuePosition(runId)`：排队索引 ZRANK，-1 表示不在排队。
- `claimOwnership(runId, instanceId) → long`：Lua `HINCRBY ownerToken 1` + 置 `RUNNING`/`ownerInstanceId`/心跳，记录不存在返回 -1。
- 令牌校验写入（不一致或记录已删除 → false 且不写入）：`saveCase(runId, token, result)`、`heartbeat(runId, token, now)`、`finish(runId, token, …)`、`markReported(runId, token, caseId)`。
- `reportedCaseIds(runId)`（cases 旁路 HASH `…:cases:reported`）；`markFailed(runId, error)`（系统写入，无令牌，提交人校验失败用）；`delete` 同时清排队索引与 reported。
- 内存实现语义一致（`ConcurrentHashMap` + `AtomicLong` 令牌 + 排序队列索引）。

### Task 2: 协调端口 + 内存实现 + 实例标识

**Files:** Create `eval/EvalWorkQueue.java`, `eval/InMemoryEvalWorkQueue.java`, `eval/EvalClusterSlots.java`, `eval/InMemoryEvalClusterSlots.java`, `eval/EvalControlBus.java`, `eval/InMemoryEvalControlBus.java`, `eval/EvalRunLocks.java`, `eval/InMemoryEvalRunLocks.java`, `eval/EvalInstanceId.java`；Test `eval/InMemoryEvalCoordinationTest.java`。

**Produces:**
- `EvalWorkQueue`：`enqueue(runId)`；`claim(block) → ClaimedTask(id, runId) | null`（消费组语义：一条消息只被一个消费者拿到，含空闲超时挂起回收）；`ack(task)`。
- `EvalClusterSlots`：`tryAcquire(instanceId, max, ttl)`、`renew(instanceId, ttl)`（不再持有返回 false）、`release(instanceId)`。
- `EvalControlBus`：`publish(runId, action)`、`subscribe(handler) → Runnable`（返回注销句柄）；订阅者在所有实例收到。
- `EvalRunLocks`：`isAvailable()`、`tryAcquire(runId) → AutoCloseable | null`。
- `EvalInstanceId`：`@Value("${kwiki.instance-id:}")` 为空时生成 `{主机名}-{pid}-{4位随机}`。

### Task 3: Redis 协调实现 + 装配

**Files:** Create `infrastructure/redis/RedisEvalWorkQueue.java`, `infrastructure/redis/RedisEvalClusterSlots.java`, `infrastructure/redis/RedisEvalControlBus.java`, `infrastructure/redis/RedisEvalRunLocks.java`, `infrastructure/redis/EvalCoordinationConfiguration.java`；Test 随 Task 7 的环境门控集成测试覆盖。

**Produces:**
- 队列：stream `kwiki:eval:queue`、消费组 `kwiki-eval-workers`、消费者 = 实例标识；`XREADGROUP BLOCK COUNT 1`、`XACK`；挂起回收 `XPENDING + XCLAIM(minIdle=60s)`（Spring Data 3.5 无 XAUTOCLAIM 封装）；组不存在时 `XGROUP CREATE`（BUSYGROUP 忽略）。
- 名额：ZSET `kwiki:eval:slots`，Lua（清过期 → `ZCARD < max` 时 `ZADD` 到期 = now+60s → 成功）；`renew` 仅在仍持有时续期；`release` `ZREM`。
- 控制：`PUBLISH kwiki:eval:control` JSON `{"runId":…,"action":"…"}`；订阅用 `RedisMessageListenerContainer`（同 `RedisModelConfigBroadcaster` 模式）。
- run 锁：委托 `KwikiDistributedLocks.acquire("eval-run:{runId}", ZERO)`（watchdog）。
- 装配：`ObjectProvider<RedisConnectionFactory>` 判定（同 `EvalRunStoreConfiguration`），无工厂时全部内存实现。

### Task 4: `EvalReportPort.upload` 取代缓冲

**Files:** Modify `eval/EvalReportPort.java`, `infrastructure/langfuse/LangfuseRestClient.java`；Test `LangfuseRestClientTest`, `LangfuseRestClientLiveTest`, `eval/InMemoryReport.java`。

**Produces:** `List<String> upload(List<TraceRecord> batch)`：同步发送 ingestion（含 spans/scores）与逐条 dataset-run-items，返回上报成功的 traceId（ingestion 失败 → 空列表；dataset-run-items 失败 → 该条不计入）；`LangfuseRestClient` 删除缓冲/`flush`/`failedTraces`；`InMemoryReport` 改为记录 uploads 并支持按 traceId 注入失败（`failTraceIds`）。

### Task 5: `EvalRunService` 重构 + `EvalWorker`

**Files:** Modify `eval/EvalRunService.java`；Create `eval/EvalWorker.java`；Test `EvalRunServiceTest`（改构造与排队断言）, `EvalRunLifecycleTest`（共享队列/控制总线）, 新增 `EvalRunQueueTest`、`EvalRunOwnershipTest`。

**Produces:**
- `start`：校验（同 prepareNew）→ `create(QUEUED)`（meta 带 submittedBy）→ `enqueue` → 返回 `QUEUED`；不再有 `eval-busy`。
- `resume`：校验（数据集/Langfuse/语料就绪 + run 可继续）→ `markQueued` CAS（失败 409 `eval-run-not-resumable`）→ `enqueue`。
- `cancel`：`QUEUED` → `cancelQueued`；`RUNNING` → 本实例直接取消，否则 `requestCancel` + `publish(control)`；心跳兜底保留。
- `delete`：`RUNNING` 且心跳新鲜 → 409；`QUEUED` → 先 `cancelQueued` 再删；其余同现状。
- `status/list`：`RunStatus` + `ownerInstanceId`、`queuePosition`（仅 `QUEUED`）。
- 执行：认领后按 `submittedBy` 重新加载用户（`AppUserRepository`，须启用管理员，否则 `markFailed(submitter-not-authorized)`）；作用域与 pageDocIds 按该用户重算；run 锁；逐题 `saveCase(token)`；按 run 缓冲 trace（≤20 条批量 `upload`，成功即 `markReported(token)`）；开始执行（含继续/接管）先补报「有检查点未上报」的题；心跳 = 令牌校验心跳 + 集群名额续期；任一令牌写入失败 → INFO `eval run ownership lost runId={} instance={}`，取消在途题静默退出，不写终态；结束时 flush 余量后令牌校验 `finish`（有未上报 → `REPORT_PARTIAL`）。
- `EvalWorker`：单守护线程循环（可配轮询间隔，默认 2s）：本实例 `Semaphore(1)` → 集群名额 → `claim`（阻塞 2s）→ 状态检查（QUEUED/过期 RUNNING 继续；cancelRequested 置 CANCELLED；缺失/其他跳过）→ 提交人校验 → `claimOwnership` → `ack` → 同步执行 → finally 释放名额；`start()/stop()` 生命周期。
- `EvalRunService` 订阅控制总线：本实例在执行的 run 收到 cancel 立即取消。

### Task 6: 控制器与管理端

**Files:** Modify `admin-frontend/src/api.ts`, `src/evalReport.ts`, `src/views/RagEvalView.vue`；Test `admin-frontend/tests/rag-eval.spec.ts`；控制器无接口变化（`RunStatus` 形状扩展，`EvalAdminControllerTest` 构造同步）。

**Produces:** `EvalRunState`/标签加「排队中」；`EvalRunStatus` + `ownerInstanceId`/`queuePosition`；运行态（RUNNING/QUEUED）持续轮询；开始按钮不再因运行中禁用，成功提示「已加入队列」；QUEUED 行显示「前面还有 N 个」、可中断；RUNNING 显示执行实例标识；排队中与运行中的中断确认文案区分。

### Task 7: Redis Lua 集成验证 + 文档 + 全量验证

**Files:** Create `infrastructure/redis/RedisEvalCoordinationLiveTest.java`（`@EnabledIfEnvironmentVariable(named = "KWIKI_IT_REDIS_URI", matches = ".+")`，无变量跳过）；Modify `docs/rag-eval.md`。

**Produces:** 真实 Redis 上验证：名额 Lua（占位/上限/过期回收/续期/释放）、状态 CAS（并发继续只有一个成功）、令牌校验写入（旧令牌被拒）、Stream 消费组（认领/ack/挂起回收）；文档补多实例运行说明与每实例限流提示（§9）；跑全部验证命令并按任务分提交。
