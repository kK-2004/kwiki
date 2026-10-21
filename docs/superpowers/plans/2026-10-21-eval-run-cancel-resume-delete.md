# 评测 run 中断 / 继续 / 删除 实现计划

> 本会话内联执行（TDD），设计见 `docs/superpowers/specs/2026-10-21-eval-run-cancel-resume-delete-design.md`。

**Goal:** 评测 run 可中断、继续、删除，状态与逐题结果持久化到 Redis，报告改为管理端下载。

**Architecture:** 新增 `EvalRunStore`（Redis / 内存两实现）承载 run 元信息、状态与逐题检查点；`EvalRunService` 改为 runner 线程统一收集结果、可取消的题目池与心跳；`EvalReportPort` 增加 `deleteRun`；控制器与管理端页面补齐接口与按钮。

**Tech Stack:** Spring Boot 3.5 / Java 21、Spring Data Redis（`StringRedisTemplate`）、Reactor、Vue 3 + Element Plus、vitest。

## Global Constraints

- 所有提交信息用简体中文，不加 Co-Authored-By 尾行。
- `application.yml` 不新增配置；新参数用 `@Value` 默认值。
- 不加数据库表；不推送分支。
- 验证：`./mvnw -q test`；`cd admin-frontend && npm run typecheck && npx vitest run && npm run build`。

---

### Task 1: `EvalRunStore` 接口 + 内存实现 + Redis 实现

**Files:** Create `eval/EvalRunStore.java`, `eval/InMemoryEvalRunStore.java`, `infrastructure/redis/RedisEvalRunStore.java`, `eval/EvalRunStoreConfiguration.java`；Test `eval/InMemoryEvalRunStoreTest.java`, `infrastructure/redis/RedisEvalRunStoreTest.java`（mock `StringRedisTemplate` 的 hash/zset 操作，验证键名与 CaseResult JSON 往返）。

**Produces:**
- `record RunMeta(String runId, String runName, String dataset, long kbId, String notes, boolean localOnly, int total, Map<String,Object> config, Instant createdAt)`
- `record RunRecord(RunMeta meta, State state, EvalSummary summary, String error, boolean reportPartial, Instant heartbeatAt, boolean cancelRequested)`
- `create(RunMeta)`, `Optional<RunRecord> find(String)`, `List<RunRecord> list(int)`, `saveCase(String, CaseResult)`, `Map<String,CaseResult> cases(String)`, `int caseCount(String)`, `setState(String, State, String error)`, `finish(String, State, EvalSummary, boolean reportPartial, String error)`, `heartbeat(String, Instant)`, `requestCancel(String)`, `delete(String)`
- `State` 从 `EvalRunService` 移到 `EvalRunState` 枚举：`RUNNING, COMPLETED, REPORT_PARTIAL, CANCELLED, FAILED`

### Task 2: `EvalReportPort.deleteRun` + Langfuse 实现

**Files:** Modify `EvalReportPort.java`, `LangfuseRestClient.java`, `InMemoryReport.java`；Test `LangfuseRestClientTest`（MockRestServiceServer 或现有测试方式）。

**Produces:** `void deleteRun(String datasetName, String runName, List<String> traceIds)`：traceIds 每批 100 调 `DELETE /api/public/traces` `{traceIds}`；再 `DELETE /api/public/datasets/{datasetName}/runs/{runName}`，404 视为成功；失败抛 `EvalReportException`。

### Task 3: `EvalRunService` 重构：检查点、中断、继续、删除、历史

**Files:** Modify `EvalRunService.java`；Delete `EvalReportWriter.java`；Test `EvalRunServiceTest.java`（改用 `InMemoryEvalRunStore`），新增 `EvalRunCancellationTest.java`（真实 `WorkflowProbe` + fake `AgenticWorkflowPort` 返回 `Flux.never().doOnCancel`）。

**Produces:** `start`, `status`, `list()`, `cancel(runId)`, `resume(user, runId)`, `delete(runId, boolean langfuse) → DeleteResult(boolean langfuseDeleted, String langfuseError)`, `report(runId) → Map<String,Object>`；`RunStatus(runId, runName, dataset, state, total, completed, summary, error, localOnly, reportPartial, createdAt)`。

测试：取消在途题（订阅被取消、该题无检查点、状态 CANCELLED、已完成题保留并上报）；继续只跑缺失题、汇总含全部题；删除清理并调用 `deleteRun` 携带确定性 traceId；RUNNING 不可删除/继续；心跳过期视为 CANCELLED；跨实例取消标志生效。

### Task 4: 控制器接口

**Files:** Modify `EvalAdminController.java`；Test 控制器单测（直接调用或 MockMvc，沿用仓库现有方式）。

### Task 5: 管理端

**Files:** Modify `admin-frontend/src/api.ts`, `src/evalReport.ts`, `src/views/RagEvalView.vue`；Test `admin-frontend/tests/rag-eval.spec.ts`。

历史表格、中断/继续确认、删除弹窗（Langfuse 复选框）、下载 JSON（Blob）；`RUN_STATE_LABELS` 增 `CANCELLED`；错误码中文翻译。

### Task 6: 文档与全量验证

**Files:** Modify `docs/rag-eval.md`（去掉 report-dir，新增 Redis 存储、中断/继续/删除、下载说明）；跑全部验证命令并提交。
