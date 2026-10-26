# 模型 token 用量监控

管理端「模型用量」页统计对话模型与 embedding 的 token 消耗。设计见
`docs/superpowers/specs/2026-10-21-model-usage-monitoring-design.md`。

## 维度

- 来源：线上（用户请求）、评测（RAG 评测，含基线检索的查询向量）、后台（索引、图谱构建等）。
- 步骤：意图路由、查询改写、Agent 规划、检索规划、直接回答、质量分析、回答生成、图谱抽取、社区摘要、图片摘要，以及查询向量、索引向量。历史记录里已下线的划词问答（`SELECTION_QUESTION`）显示为「划词问答（已下线）」。
- provider / 模型、用户（后台任务为「系统」）。

## 数据

- `model_usage_minute`：分钟聚合，保留 `kwiki.model-usage.retention-days`（默认 180）天。
- `model_usage_call`：逐次调用明细（含 requestId、失败类型），保留 `kwiki.model-usage.call-retention-days`（默认 7）天，便于按 requestId 排查。
- 采集先进内存队列（上限 50,000 条，满时丢弃并计 `kwiki_model_usage_dropped_total`），每 `kwiki.model-usage.flush-interval-ms`（默认 10000）毫秒落库；进程崩溃最多丢最近一个周期的数据。
- 每日 03:30（Asia/Shanghai）在分布式锁下清理过期数据。
- provider 未返回用量时 token 记 0，调用数照常统计。

### 口径说明

- 对话模型：一次 LLM 调用（含流式）记一条事件，「调用数」即调用次数。
- embedding：每次批量 HTTP 请求记一条事件（`usage.prompt_tokens` 按请求返回，只有按请求归属才精确；该口径也与 provider 的请求计费一致），因此「调用数」为 HTTP 请求批次数——一次大批量索引（如 1000 段、批大小 10）会计为 100 次调用，与对话模型的调用数不宜直接相加比较。
- 图片摘要（`VISION_SUMMARY`）：视觉客户端自行调用 `/chat/completions`，不经过对话模型监听器，由 `QwenVisionSummaryClient` 解析 `usage.prompt_tokens` / `usage.completion_tokens` 直接记录；一次摘要（含内部重试）记一条事件，最终失败记失败事件（失败类型为异常类名，如 `VisionSummaryException`）。在索引任务中执行，来源通常为后台。
- embedding 失败类型：`rejected-<状态码>`（provider 拒绝）、`retries-exhausted`、`dimension-mismatch`、`count-mismatch`、`request-failed` 等，见 `QwenEmbeddingClient`。

## 页面

- 时间：今天（默认，每分钟自动刷新）/ 昨天 / 近 7 天 / 近 30 天 / 自定义（最长 366 天）；分桶自动选择 5 分钟、1 小时或 1 天，并按浏览器时区对齐。
- 维度树：按步骤（步骤 → 模型）或按模型（provider/模型 → 步骤）。一级节点全选时合并为一条线，部分勾选时按勾选的叶子拆线；自动刷新保留手动勾选，新出现的叶子默认勾选。
- 单位自适应：k / M / B。

## 排查示例

    SELECT occurred_at, step, model, input_tokens, output_tokens, status, error_type
    FROM model_usage_call WHERE request_id = '<requestId>' ORDER BY occurred_at;
