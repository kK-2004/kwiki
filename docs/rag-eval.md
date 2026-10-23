# RAG 召回评测运行说明

基于关键词的分层召回评测：DragonBall 中文语料（108 篇）+ 200 题嵌套测试集（50q ⊂ 100q ⊂ 200q），
结果写入 Langfuse Cloud，run 状态与逐题结果保存在 Redis（管理端可下载 JSON 报告）。设计见
`docs/superpowers/specs/2026-10-20-langfuse-rag-eval-design.md`、
`docs/superpowers/specs/2026-10-21-eval-run-cancel-resume-delete-design.md`（中断 / 继续 / 删除）与
`docs/superpowers/specs/2026-10-22-eval-multi-instance-design.md`（多实例执行）。

## 1. 配置（Nacos 应用配置）

| 键 | 默认 | 说明 |
|---|---|---|
| `kwiki.langfuse.host` | `https://cloud.langfuse.com` | 美区为 `https://us.cloud.langfuse.com` |
| `kwiki.langfuse.public-key` | 空 | Langfuse 项目 Public Key |
| `kwiki.langfuse.secret-key` | 空 | Langfuse 项目 Secret Key（勿入仓库） |
| `kwiki.langfuse.max-requests-per-minute` | `90` | 客户端主动限速；Langfuse Cloud 公共 API 默认 100 次/分钟，升级套餐后可调高 |
| `kwiki.eval.concurrency` | `2` | 单个 run 同时评测的题数（1~8），受模型限流约束 |
| `kwiki.eval.max-concurrent-runs` | `2` | 集群同时执行的 run 上限（每实例至多执行 1 个），超出的 run 排队 |
| `kwiki.instance-id` | `{主机名}-{pid}-{4位随机}` | 实例标识（认领消费者名、执行实例展示）；未配置时每次启动重新生成 |

未配置 key 时应用正常启动；评测只能以 `localOnly=true` 运行。

run 存储与执行协调：启用 Redis（`kk.common.redis.enabled=true`）时，run 状态、逐题检查点、
执行队列（Redis Stream）、集群名额（ZSET）与跨实例控制通知（Pub/Sub）都在 Redis 上，多实例部署时
管理端请求落到任意实例效果一致；未启用 Redis 时全部退化为进程内实现（单实例排队执行）。
run 记录不设 TTL，由管理端删除。不再写本地报告文件（原 `kwiki.eval.report-dir` 已移除）。

| Redis 键 / 频道 | 类型 | 内容 |
|---|---|---|
| `kwiki:eval:runs` | ZSET | runId，按创建时间排序（历史列表） |
| `kwiki:eval:queued` | ZSET | 排队中的 runId，按 queuedAt 排序（计算「前面还有 N 个」） |
| `kwiki:eval:run:{runId}` | HASH | `meta`（runName、dataset、kbId、notes、localOnly、submittedBy、配置快照）、`state`（QUEUED/RUNNING/…）、`heartbeatAt`、`queuedAt`、`ownerInstanceId`、`ownerToken`（写入令牌）、`cancelRequested`、`summary`、`error`、`reportPartial` |
| `kwiki:eval:run:{runId}:cases` | HASH | caseId → 逐题结果 JSON（检查点） |
| `kwiki:eval:run:{runId}:cases:reported` | HASH | 已确认上报到 Langfuse 的 caseId（继续 / 接管时据此补报） |
| `kwiki:eval:queue` | Stream | 待执行任务；消费组 `kwiki-eval-workers`，消费者名 = 实例标识，读取后未确认的任务空闲超 60 秒被其他实例接管；确认时原子地 XACK + XDEL，已确认的消息不保留 |
| `kwiki:eval:slots` | ZSET | 集群执行名额（member = 实例标识、score = 按 Redis 服务器时间计的到期毫秒），执行期间随心跳续期，实例宕机后到期自动释放 |
| `kwiki:eval:control` | Pub/Sub | 跨实例控制通知（如取消）；通知丢失时由执行实例的心跳轮询兜底 |

上报的 trace 含文档片段与回答，评测知识库只放公开数据集与明确同意上传的文档。

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
| 中断 | `POST /runs/{runId}/cancel` | 排队中（`QUEUED`）直接取消；运行中（`RUNNING`）写取消标志并经控制通知广播，执行实例立即取消在途题（基线检索线程被中断，完整工作流的订阅被 dispose，从而关闭 `RunContext`），不计入结果；已完成的题保留，缓冲中的 trace 照常上报；状态 `CANCELLED` |
| 继续 | `POST /runs/{runId}/resume` | 仅限 `CANCELLED` / `FAILED`；CAS 置回 `QUEUED` 并入队，由空闲实例认领执行（可跨实例，并发重复继续只有一个成功）；只跑没有检查点的题（中断时在途的题会重新召回、重新生成），沿用同一 runId / runName / 配置快照，Langfuse 上仍是同一个 dataset run；结束后汇总全部题 |
| 删除 | `DELETE /runs/{runId}?langfuse=true` | 运行中不可删除（先中断）；排队中先取消再删除；删除 Redis 中的 run 与逐题结果。`langfuse=true` 时同时调用 Langfuse `DELETE /api/public/traces`（traceId 由 runName+题号确定性计算，每批 100）与 `DELETE /api/public/datasets/{dataset}/runs/{runName}`（404 视为已删除）；Langfuse 删除异步且不可恢复，失败时 Redis 中的记录仍会删除并在界面提示手动清理 |

- 中断请求落在不持有该 run 的实例上时写取消标志并广播控制通知；持有实例收到通知立即取消，
  通知丢失时在下一次心跳（5 秒）兜底执行。
- 状态为 RUNNING 但心跳超过 2 分钟未更新（实例失联）的 run 显示为「已中断」（error=`interrupted`），可继续或删除。
- 应用正常关闭时，本实例在途的 run 先被取消并收尾（最多等 10 秒），记为 `CANCELLED`（error=`instance-shutdown`），可在任意实例继续。
- 跨实例的时间判断（集群名额到期、认领与继续时的心跳过期、心跳时间本身）一律在 Lua 中取 Redis 服务器时间，
  不受各实例本地时钟偏差影响；管理端的「已中断」显示仍按查询实例的本地时钟判断，仅影响展示。
- 执行出错的题已作为结果记录（不计入召回分母），继续时不会重跑；如需重跑，新建一个 run。
- 认领时按 `submittedBy` 重新校验提交人：已不是启用管理员的 run 记为 `FAILED: submitter-not-authorized`，
  检索作用域也按该用户在执行时重新计算（不沿用提交时的权限）。

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

## 7. 多实例执行

- 每个实例同时最多执行 1 个 run（进程内信号量），集群同时执行的 run 总数不超过
  `kwiki.eval.max-concurrent-runs`（默认 2），超出的 run 排队；「开始评测」不再因已有评测在跑而报错，
  而是提示「已加入队列」。管理端「排队中」标签显示前面还有多少个，运行中显示执行实例标识。
- run 的写入（逐题检查点、心跳、终态、已上报标记）一律经认领时发放的写入令牌校验：执行实例宕机、
  任务被其他实例接管后，旧实例的写入被拒绝并静默退出（日志 `eval run ownership lost`），不会覆盖新实例的数据。
- Langfuse 上报按 run 缓冲（满 20 条或收尾时同步发送），逐题检查点带「已上报」标记；
  执行实例宕机后，继续 / 接管该 run 的实例先把「有检查点但未上报」的题补报，再继续未完成的题，
  Langfuse 上的 dataset run 最终完整。上报失败的 run 终态为「完成（上报不完整）」。
- **不在本期：多实例全局限流。** `kwiki.langfuse.max-requests-per-minute` 与模型 provider 的并发/QPS 限制
  均按实例计算，多实例部署时需按实例数调低每个实例的配额（例如 2 实例时各配 45 次/分钟），
  或另行引入 Redis 全局令牌桶。

## 评测运行注意

- 评测以「无人交互」方式调用问答工作流（`RouteInput.NON_INTERACTIVE`）：路由不反问、规划器不提供 ask_user、不改道直答。
  线上同一问题可能触发澄清，评测中会直接检索作答。
- 质量评审的 thinking 跟随「模型配置 → 用途分配」中「质量分析」的设置（开 / 关如实下发 `enable_thinking`；留空为 provider 默认，不下发该参数——始终思考的模型只能留空或设为开）；评审节点时限
  `kwiki.agentic.quality-deadline`（默认 `120s`）。开启思考时请确认 provider 的请求超时不小于该时限，
  且问答运行总时限 `kwiki.agentic.timeout`（默认 `300s`）足够容纳生成与评审；评测单题等待上限为 330 秒。
- 单路检索时限 `kwiki.retrieval.branch-deadline`（默认 `5s`）同时作用于线上问答与评测工作流。到 ES 的链路延迟高时
  （日志 `es search slow` 中 `esTookMs` 很小而 `searchMs` 很大），可在 Nacos 临时调大，根治办法是让后端走内网直连 ES。
