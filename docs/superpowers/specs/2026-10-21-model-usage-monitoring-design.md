# 模型 token 用量监控设计

日期：2026-10-21
状态：待评审

## 1. 目标与范围

在管理端提供 LLM 与 embedding 的 token 用量监控：按调用来源（线上 / 评测 / 后台）区分、可按用户筛选，按步骤（`ModelPurpose` 与 embedding 步骤）× 具体模型、以及 provider-模型两个维度统计，支持任意时间范围的趋势图，默认显示当天。

不在本期：费用（单价）换算、告警、按知识库筛选（一次请求可跨多个知识库或不限定知识库，归属口径不唯一）。

## 2. 维度定义

| 维度 | 取值 |
|---|---|
| 来源 `source` | `ONLINE`（用户请求）、`EVAL`（RAG 评测）、`BACKGROUND`（索引、图谱构建等非用户请求） |
| 步骤 `step` | `ModelPurpose` 全部 11 个枚举名（ROUTER、REWRITE、AGENT_PLANNER、RETRIEVAL_PLANNER、DIRECT_ANSWER、QUALITY_ANALYZER、ANSWER、SELECTION_QUESTION、GRAPH_EXTRACTION、GRAPH_SUMMARY、VISION_SUMMARY），外加 `QUERY_EMBEDDING`（查询向量）、`INDEX_EMBEDDING`（索引向量）；中文名取 `ModelPurpose.label()`，embedding 两项为「查询向量」「索引向量」 |
| provider | 模型目录中的 provider 名称（`ModelProvider.name()`） |
| 模型 `model` | `modelName` |
| 用户 `user_id` | 发起调用的用户：线上为请求用户；评测为触发评测的管理员；后台任务为空（界面显示「系统」） |

指标：调用数、失败数、输入 token、输出 token、总 token（输入 + 输出）、总耗时（毫秒）。embedding 只有输入 token；provider 未返回用量时 token 记 0，调用数照常累加。

## 3. 采集

### 3.1 对话模型

- `ClientSpec` 新增 `step`（`ModelPurpose` 名）与 `providerName`，二者纳入 `fingerprint()`：同一模型用于不同步骤时各有独立客户端实例（最多 11 个），使监听器在构造时即知道步骤、provider 与模型。
- `OpenAiModelClientFactory` 为对话与流式客户端挂载 `ModelUsageListener`（与已有的 `ModelCallErrorLogger` 并列）：
  - `onRequest`：在调用线程上用 `ModelUsageContext.currentSource()` 判定来源，连同 `System.nanoTime()` 存入 `attributes`；
  - `onResponse`：读取 `chatResponse().tokenUsage()` 的输入/输出 token，计算耗时，提交一条成功记录；
  - `onError`：提交一条失败记录，带异常简单类名。
- requestId 与用户：`onRequest` 时若 `RunContext.current()` 存在，取其 `requestId` 与 `scope.userId()` 存入 `attributes`，随记录落库；显式 `ModelUsageContext` 作用域也可携带用户 id（评测题的基线检索用触发评测的管理员）。

### 3.2 embedding

- `QwenEmbeddingClient.EmbeddingResponse` 增加 `usage`（`prompt_tokens`、`total_tokens`，缺失时为 null），每次批量请求（含重试成功后）提交一条记录；失败时提交失败记录。
- 步骤由 `ModelUsageContext` 的步骤覆盖决定：`IndexingWorker` 调用 `pipeline.embeddings().embed(...)` 与图谱社区索引写入（`CommunityIndexWriter`）时设为 `INDEX_EMBEDDING`；未设置时默认 `QUERY_EMBEDDING`。
- `QwenEmbeddingClient` 由 `ModelClientFactory.embedding(...)` 构造，构造参数增加 provider 名与 `ModelUsageRecorder`。

### 3.3 来源判定 `ModelUsageContext`

线程级上下文（`ThreadLocal`，`try-with-resources` 作用域），判定顺序：

1. 显式作用域：`ModelUsageContext.open(source[, stepOverride])` 设置的来源；
2. `RunContext.current()` 存在：取 `RunContext.usageSource()`（默认 `ONLINE`）；
3. 否则 `BACKGROUND`。

设置点：

- 评测：`EvalRunService` 执行每题时打开 `EVAL` 作用域（覆盖基线检索的查询向量）；工作流在 agentic 线程上运行，因此 `AgenticWorkflowPort.answerInSession(..., RunObserver observer)` 的评测调用需让 `RunContext` 带上 `EVAL`：`RunObserver` 新增默认方法 `UsageSource usageSource()`（默认 `ONLINE`），`WorkflowProbe` 传入的观测者返回 `EVAL`，`LangGraphAgenticWorkflow` 在创建 `RunContext` 后据此设置。
- 后台：索引任务（`IndexingWorker`）、图谱构建（`GraphBuildExecutor`）、多模态图片摘要在各自入口打开 `BACKGROUND` 作用域；即使漏设，规则 3 也会归为后台。

## 4. 存储（Flyway `V43__model_usage.sql`）

```sql
CREATE TABLE model_usage_minute (
  bucket_start   DATETIME     NOT NULL,           -- UTC，截断到分钟
  source         VARCHAR(16)  NOT NULL,
  step           VARCHAR(32)  NOT NULL,
  provider       VARCHAR(128) NOT NULL,
  model          VARCHAR(128) NOT NULL,
  user_id        BIGINT       NOT NULL DEFAULT 0, -- 0 表示系统（后台任务）
  calls          BIGINT       NOT NULL DEFAULT 0,
  failures       BIGINT       NOT NULL DEFAULT 0,
  input_tokens   BIGINT       NOT NULL DEFAULT 0,
  output_tokens  BIGINT       NOT NULL DEFAULT 0,
  latency_ms     BIGINT       NOT NULL DEFAULT 0,
  PRIMARY KEY (bucket_start, source, step, provider, model, user_id),
  KEY idx_usage_minute_step (step, bucket_start),
  KEY idx_usage_minute_model (provider, model, bucket_start),
  KEY idx_usage_minute_user (user_id, bucket_start)
);

CREATE TABLE model_usage_call (
  id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
  occurred_at    DATETIME(3)  NOT NULL,            -- UTC
  source         VARCHAR(16)  NOT NULL,
  step           VARCHAR(32)  NOT NULL,
  provider       VARCHAR(128) NOT NULL,
  model          VARCHAR(128) NOT NULL,
  user_id        BIGINT       NOT NULL DEFAULT 0,
  request_id     VARCHAR(64)  NULL,
  input_tokens   INT          NOT NULL DEFAULT 0,
  output_tokens  INT          NOT NULL DEFAULT 0,
  latency_ms     INT          NOT NULL DEFAULT 0,
  status         VARCHAR(16)  NOT NULL,            -- SUCCESS / FAILED
  error_type     VARCHAR(128) NULL,
  KEY idx_usage_call_time (occurred_at),
  KEY idx_usage_call_request (request_id)
);
```

迁移版本号以实现时 `db-migration` 技能核对结果为准。

### 4.1 写入

- `ModelUsageRecorder.record(UsageEvent)`：放入有界内存队列（容量 50,000，满时丢弃并计数 `kwiki_model_usage_dropped_total`），绝不阻塞模型调用。
- `ModelUsageFlusher`：每 10 秒取空队列，在内存按分钟组合预聚合后对 `model_usage_minute` 执行 `INSERT … ON DUPLICATE KEY UPDATE col = col + VALUES(col)`；明细批量插入 `model_usage_call`。写库失败记 WARN 与指标，数据丢弃（不重试，避免重复累加）。
- 应用关闭时执行一次最终 flush。
- 进程崩溃最多丢失最近 10 秒的数据，可接受。

### 4.2 清理

`ModelUsageRetentionJob` 每天 03:30（服务器时区）在 Redis 分布式锁 `model-usage-retention` 下执行：删除 `bucket_start` 早于 180 天的聚合行、`occurred_at` 早于 7 天的明细行，分批删除（每批 10,000 行）。保留天数配置：`kwiki.model-usage.retention-days`（默认 180）、`kwiki.model-usage.call-retention-days`（默认 7），用 `@Value` 默认值，实际值放 Nacos。

## 5. 查询接口（`/api/v1/admin/model-usage`，`hasRole('ADMIN')`）

时间参数均为 ISO-8601 instant（前端按浏览器本地时区换算当天起止）；`tzOffsetMinutes` 为浏览器相对 UTC 的偏移（东八区为 480），用于让「按天/按小时」分桶对齐本地零点。`sources` 为逗号分隔，缺省为全部；`users` 为逗号分隔的用户 id（`0` 表示系统），缺省为全部。

| 接口 | 说明 |
|---|---|
| `GET /users?from&to&sources` | 范围内有调用的用户：`[{userId, displayName, totalTokens}]`，按用量降序；`userId=0` 显示为「系统」；显示名关联 `app_user.display_name` |
| `GET /series?from&to&sources&users&bucket&tzOffsetMinutes` | `bucket=auto\|5m\|1h\|1d`（auto：范围 ≤ 1 天用 5m，≤ 7 天用 1h，否则 1d；桶数超过 2,000 时自动升粒度）。返回 `bucketSeconds`、`buckets: [epochMillis]` 与按「步骤 × provider × 模型」的最细粒度序列 `series: [{step, stepLabel, provider, model, calls[], failures[], inputTokens[], outputTokens[], latencyMs[]}]`，缺失桶补 0 |

两棵维度树（步骤 → 模型；provider/模型 → 步骤，节点均按总 token 降序）、折线分组、汇总卡片与明细表都由前端基于 `/series` 的同一份数据计算：切换指标、勾选维度不需重新请求，计算逻辑为纯函数便于测试。

分桶 SQL 使用 `TIMESTAMPDIFF(SECOND, '1970-01-01 00:00:00', bucket_start)` 计算秒数（不依赖数据库会话时区），写入时以 UTC 的 `LocalDateTime` 绑定参数。

## 6. 管理端「模型用量」页面

- 依赖：新增 `echarts` 与 `vue-echarts`，按需引入折线图、图例、提示框、数据缩放组件。
- 路由 `/admin/model-usage`，侧边栏「模型用量」。
- 控制栏：
  - 时间：今天（默认）/ 昨天 / 近 7 天 / 近 30 天 / 自定义（`el-date-picker` datetimerange）；
  - 来源：线上 / 评测 / 后台（多选，默认全选）；
  - 用户：可搜索多选下拉（选项来自 `/users`，显示名与用量），默认全部；
  - 指标：总 token / 输入 / 输出 / 调用数 / 失败数；
  - 维度：「按步骤」与「按模型」切换，各配一个可勾选的 `el-tree`（步骤 → 模型；provider/模型 → 步骤），默认全选；
  - 图表类型：折线 / 堆叠面积。
- 主体：汇总卡片 → 趋势图 → 明细表（按列排序）。
- 单位自适应 `formatTokens(n)`：`< 1000` 原样；`< 1e6` 为 `k`；`< 1e9` 为 `M`；否则 `B`；保留 3 位有效数字（如 `12.3k`、`4.56M`、`1.20B`）。卡片、图表坐标轴与提示框、明细表统一使用。
- 时间预设、来源、用户、指标、维度与图表类型记在 `localStorage`，刷新后恢复；维度树每次加载数据后默认全选；当天视图每 60 秒自动刷新。
- 折线分组规则：某个一级节点（步骤或模型）下的叶子全部勾选时合并为一条线（按一级节点命名），部分勾选时每个勾选的叶子各一条线（「步骤 / 模型」或「模型 / 步骤」）；汇总卡片与明细表只统计勾选的叶子。

## 7. 错误处理

- 采集侧任何异常（解析用量、入队）只记 DEBUG/指标，不影响模型调用结果。
- 查询参数非法（时间倒置、范围超过 366 天、未知 metric）返回 400 与稳定错误码。
- 数据库不可用时查询接口返回 503；采集侧 flush 失败按 4.1 处理。

## 8. 测试

- 后端单测：来源判定顺序；用户 id 取值（线上、评测、后台为 0）；监听器解析 token、记录失败、取 requestId；embedding usage 解析与缺失处理；预聚合（同分钟同组合累加）；series 分桶、对齐时区、补 0、自动升粒度；SQL 与参数绑定（mock JdbcOperations）；清理分批删除。
- 集成：仓储的 upsert、分桶查询、用户查询与清理在提供 `KWIKI_IT_MYSQL_URL` 时针对真实 MySQL 验证（沿用 `V3MigrationContractTest` 的写法）。
- 前端：`formatTokens` 边界值；由序列构建两棵树；按勾选状态合并/拆分折线；汇总与明细计算；页面渲染（图表组件 mock）；默认今天与本地时区起止。
