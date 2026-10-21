# RAG 召回评测运行说明

基于关键词的分层召回评测：DragonBall 中文语料（108 篇）+ 200 题嵌套测试集（50q ⊂ 100q ⊂ 200q），
结果写入 Langfuse Cloud，run 状态与逐题结果保存在 Redis（管理端可下载 JSON 报告）。设计见
`docs/superpowers/specs/2026-10-20-langfuse-rag-eval-design.md` 与
`docs/superpowers/specs/2026-10-21-eval-run-cancel-resume-delete-design.md`（中断 / 继续 / 删除）。

## 1. 配置（Nacos 应用配置）

| 键 | 默认 | 说明 |
|---|---|---|
| `kwiki.langfuse.host` | `https://cloud.langfuse.com` | 美区为 `https://us.cloud.langfuse.com` |
| `kwiki.langfuse.public-key` | 空 | Langfuse 项目 Public Key |
| `kwiki.langfuse.secret-key` | 空 | Langfuse 项目 Secret Key（勿入仓库） |
| `kwiki.langfuse.max-requests-per-minute` | `90` | 客户端主动限速；Langfuse Cloud 公共 API 默认 100 次/分钟，升级套餐后可调高 |
| `kwiki.eval.concurrency` | `2` | 同时评测的题数（1~8），受模型限流约束 |

未配置 key 时应用正常启动；评测只能以 `localOnly=true` 运行。

run 存储：启用 Redis（`kk.common.redis.enabled=true`）时，run 状态与逐题检查点写入 Redis（不设 TTL，由管理端删除），
应用重启后可在历史列表中看到并继续未完成的 run；未启用 Redis 时退化为进程内存储，重启即丢失。
不再写本地报告文件（原 `kwiki.eval.report-dir` 已移除）。

| Redis 键 | 类型 | 内容 |
|---|---|---|
| `kwiki:eval:runs` | ZSET | runId，按创建时间排序（历史列表） |
| `kwiki:eval:run:{runId}` | HASH | `meta`（runName、dataset、kbId、notes、localOnly、配置快照）、`state`、`heartbeatAt`、`cancelRequested`、`summary`、`error`、`reportPartial` |
| `kwiki:eval:run:{runId}:cases` | HASH | caseId → 逐题结果 JSON（检查点） |上报的 trace 含文档片段与回答，
评测知识库只放公开数据集与明确同意上传的文档。

## 2. 步骤（均需管理员令牌）

1. 新建一个专用知识库（例如「RAG 评测」），记下 `kbId`。
2. 导入语料：`POST /api/v1/admin/eval/corpus/import?kbId={kbId}&corpus=dragonball-zh`（幂等，可重复调用）。
3. 等待索引完成：评测启动时会检查，未就绪返回 `corpus-not-ready`。
4. 同步测试集到 Langfuse：`POST /api/v1/admin/eval/datasets/sync`，生成 `kwiki-rag-50q/100q/200q`。
5. 启动评测：`POST /api/v1/admin/eval/runs`，body：
   `{"dataset":"50q","kbId":123,"runName":"baseline-50q","notes":"现状基线","localOnly":false}`
6. 查看进度：`GET /api/v1/admin/eval/runs/{runId}`；完成后在 Langfuse 的 Datasets → Runs 中对比。
7. 下载报告：`GET /api/v1/admin/eval/runs/{runId}/report`（管理端「下载 JSON」），结构为 `runName`、`config`、`summary`、`cases`。

## 3. 中断 / 继续 / 删除

管理端「RAG 评测」页的「历史评测」表格提供以下操作（`GET /api/v1/admin/eval/runs` 列出最近 50 个）。

| 操作 | 接口 | 说明 |
|---|---|---|
| 中断 | `POST /runs/{runId}/cancel` | 正在执行的题立即取消（基线检索线程被中断，完整工作流的订阅被 dispose，从而关闭 `RunContext`），不计入结果；已完成的题保留，缓冲中的 trace 照常上报；状态 `CANCELLED` |
| 继续 | `POST /runs/{runId}/resume` | 仅限 `CANCELLED` / `FAILED`；只跑没有检查点的题（中断时在途的题会重新召回、重新生成），沿用同一 runId / runName / 配置快照，Langfuse 上仍是同一个 dataset run；结束后汇总全部题 |
| 删除 | `DELETE /runs/{runId}?langfuse=true` | 运行中不可删除（先中断）；删除 Redis 中的 run 与逐题结果。`langfuse=true` 时同时调用 Langfuse `DELETE /api/public/traces`（traceId 由 runName+题号确定性计算，每批 100）与 `DELETE /api/public/datasets/{dataset}/runs/{runName}`（404 视为已删除）；Langfuse 删除异步且不可恢复，失败时 Redis 中的记录仍会删除并在界面提示手动清理 |

- 中断请求落在不持有该 run 的实例上时写取消标志，持有实例在下一次心跳（5 秒）时执行取消。
- 状态为 RUNNING 但心跳超过 2 分钟未更新（应用重启或实例失联）的 run 显示为「已中断」（error=`interrupted`），可继续或删除。
- 执行出错的题已作为结果记录（不计入召回分母），继续时不会重跑；如需重跑，新建一个 run。

## 4. 指标

- 每层两个指标：`coverage@{层}`（关键词覆盖率均值）、`fullHit@{层}`（全部关键词命中的题占比）。
- 层：`bm25@50`、`vector@50`、`pool@50`、`fused@8`、`fused@20`（原始 query 基线），`context`、`answer`（完整工作流）。
- 按来源文档计分：同领域文档模板相同、关键词大量重复，因此 chunk 层与 `context` 层只认来自该题 `sourceDocIds`
  对应页面（标题 `{主体名}-{doc_id}` 与语料 manifest 对应）的片段，别家文档的同类段落即使含关键词也不算命中；
  `answer` 层仍按回答全文匹配；自建题（`sourceDocIds` 为空）不过滤。
- `nonSourceFiltered`：每层被排除的非来源片段数（JSON 报告逐题 `nonSourceFiltered`、Langfuse 各层 span output），
  数值高说明检索被同模板文档干扰严重。
- 首次丢失层（trace metadata `losses` 与 JSON 报告 `lossMatrix`）：
  `GENERATION_MISS`（上下文有、回答无）、`RECALL_MISS`（两路 Top-50 都没有）、`RANK_MISS_20`、`RANK_MISS_8`、`CONTEXT_MISS`（在 Top-8 却没进最终上下文）。
- `contextGains`：基线候选池没有、但改写/图增强后进入上下文的关键词组数。
- 负样本 `refusal`：终态 insufficient，或回答匹配 `RefusalDetector.DEFAULT_PATTERNS` 中的拒答表达（如「无法确定」「未明确说明」「没有提供」「未记载」）记 1；分数备注写明命中的原文片段，便于人工核对。
- 回答层受生成温度（0.2）影响有少量波动，结论性对比建议同配置跑两次。

## 5. 对比改进

每次检索改动后用相同 dataset 跑一次，`notes` 写清改动（如「ik 分词」「contextual chunk」「rerank」），
在 Langfuse 中按 run 对比各层分数，用下载的 JSON 报告中的 `lossMatrix` 判断丢失集中在哪一层。

## 6. 维护测试集

见 `src/main/resources/eval/datasets/README.md`。修改后运行 `./mvnw -q -Dtest=EvalDatasetFileTest test`，
再调用一次数据集同步。
