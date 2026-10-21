# Langfuse RAG 召回评测设计

日期：2026-10-20
状态：待评审

## 1. 目标与范围

在 kwiki 内实现可复现的 RAG 召回评测，测试集与评测结果在 Langfuse Cloud 中维护与对比，用真实评测数据驱动后续检索改进。

本期范围：

- 评测语料（DragonBall 中文）导入独立评测知识库；
- 50q ⊂ 100q ⊂ 200q 三个嵌套测试集，每题包含中文 query 与「必须包含的关键词集合」；
- 进程内评测执行器：分层统计 chunk 召回率与回答召回率，并对每个漏召关键词做「首次丢失层」归因；
- 结果写入 Langfuse（dataset run + trace + score），本地留一份 JSON 报告；
- 运行观测扩展点 `RunObserver`，为将来的线上追踪与 agent 评测铺路。

不在本期：线上真实请求上报 Langfuse、LLM-as-judge 评分、agent 轨迹评测、检索改进本身（由评测结果驱动，另立变更）。

## 2. 语料与测试集

### 2.1 数据来源

[DragonBall](https://github.com/OpenBMB/RAGEval)（RAGEval，ACL 2025）中文部分，许可证 CC BY-NC-SA 4.0：仅用于内部评测，仓库内衍生文件附署名与同许可声明。

- 文档 108 篇：Finance 40（均 4087 字）、Law 30（均 2570 字）、Medical 38（均 1688 字）；
- 中文 query 约 3400 道，`query_type` 七类：事实性问题、多跳推理问题、总结性问题、多文档信息整合问题、多文档对比问题、多文档时间序列问题、无关无解问；
- 每题带 `ground_truth.doc_ids / references / keypoints`。

已知局限：文档短（多为 1~2 个父块）、几乎无 Markdown 标题、无 PDF/表格/图片、同领域文档同模板生成（干扰强）。因此本数据集对「标题上下文缺失」「PDF 无结构」「多模态」不敏感，召回率会比真实文档偏乐观。用户后续补充的 PDF/多模态文档以 `source: "custom"` 追加，弥补这部分。

### 2.2 语料导入

- 转换产物：`src/main/resources/eval/corpus/dragonball-zh/{domain}-{docId}.md`，附 `NOTICE.md`（署名与许可）。
- 页面标题：统一为 `{主体名}-{doc_id}`，主体名 Finance 取 `company_name`、Law 取 `court_name`、Medical 取 `hospital_patient_name`（Finance 中 doc 4 与 doc 24 同为「创新科技有限公司」，必须带 doc_id 去重）。正文为原始 `content`，不改写、不加标题，保证评测的就是原始文档的切块效果。
- 接口：`POST /api/v1/admin/eval/corpus/import?kbId={kbId}&corpus=dragonball-zh`，管理员专用。逐篇调用 `WikiTreeService.createNode`（`TYPE_PAGE`）→ `PageRevisionService.saveDraft` → `publish`，与用户手动建页走同一条发布与索引链路（`publish` 在同事务内入队索引任务）；按标题幂等，已存在的页面跳过。返回创建/跳过的页面列表；索引是否完成由评测启动前的就绪检查（`PageIndexStatusService`）判断。
- 后续用户补充的 PDF/多模态文档通过普通上传进入同一评测 KB，无需额外适配（评测按关键词匹配，不依赖文档 ID 映射）。

### 2.3 测试集文件

唯一事实来源：`src/main/resources/eval/datasets/kwiki-rag.jsonl`，每行一条：

```json
{
  "id": "db-zh-fin-0001",
  "source": "dragonball",
  "tier": 50,
  "category": "多跳推理问题",
  "domain": "Finance",
  "query": "……",
  "keywords": [["10,000吨", "1万吨"], ["2017年4月"], ["废水泄漏"]],
  "negative": false,
  "sourceDocIds": [5],
  "keypoints": ["……"]
}
```

- `tier`：该题首次纳入的层级（50/100/200）。`tier=50` 的题属于全部三个集合，`tier=100` 属于 100q 与 200q，依此类推。
- `keywords`：关键词组列表，组内为同义别名，命中任一别名即该组命中。
- `negative=true`：无关无解问，`keywords` 为空，预期结果为证据不足。
- `keypoints`：保留原始要点，供将来 LLM-as-judge 使用，本期不参与计分。

### 2.4 抽样与关键词规则

- 从中文 query 中按 `query_type` 分层抽 200 题；领域比例接近语料比例（Finance:Law:Medical ≈ 4:3:4）；负样本约 8%（16 题）。
- 嵌套：先抽 200，再从中按同样的分层比例抽 100，再从 100 中抽 50。三个集合题型分布一致。
- 排除来源文档含同名主体文档（doc 4、doc 24）的题，避免标准答案歧义；排除非负样本中 `references` 为空的题。
- 抽样使用固定随机种子，抽样脚本与种子写入 `src/main/resources/eval/datasets/README.md`，可复现。
- 关键词提炼规则（由 Claude 生成，逐题人工可审）：
  1. 只取回答该题必需的信息单元：数字（含单位）、日期、主体名、事件/措施短语；每题 2~6 组；
  2. 不收录题干中已出现的词（否则召回文本因含题干词而虚高）；
  3. 数字与日期给出常见写法别名（`10,000吨`/`1万吨`/`10000吨`）；
  4. 关键词必须能在 `references` 原文中逐字找到（至少一个别名），生成后由校验脚本断言；
  5. 多文档题的关键词需覆盖各来源文档，否则无法衡量跨文档召回。

## 3. 指标

### 3.1 关键词匹配

`KeywordMatcher`：对被匹配文本与关键词统一做 NFKC 归一化、转小写、去除全部空白后做子串匹配。数字中的千分位逗号在归一化时去除（`10,000` 与 `10000` 等价）。

### 3.2 分层统计

每层输出两个指标：

- 关键词覆盖率 `coverage`：每题「命中组数 / 总组数」，对非负样本取平均；
- 完全命中率 `fullHit`：全部组都命中的非负样本占比。

| 层 | 被匹配文本 | 含义 |
|---|---|---|
| `bm25@50` | BM25 单路 Top-50 中来源文档的子块正文 | 关键词分支能否搜到 |
| `vector@50` | 向量单路 Top-50 中来源文档的子块正文 | 语义分支能否搜到 |
| `pool@50` | 上两者并集 | 候选池上限 |
| `fused@8` | 与 QA 基础阶段一致：两路各 Top-20（`base-branch-topk`）RRF 后取 Top-8（`base-final-topk`） | 基础阶段排序质量 |
| `fused@20` | 与 QA 扩大阶段一致：两路各 Top-50 RRF 后取 Top-20（`expanded-final-topk`） | 扩大阶段排序质量 |
| `context` | 完整 QA 链路最终送入生成的上下文中来源文档的证据段（parent 或 child，含改写、图增强、阶梯扩展后的结果） | 实际喂给模型的证据 |
| `answer` | 生成的回答文本 | 回答召回率 |

**按来源文档计分**：DragonBall 同领域文档按同一模板生成，关键词（如「2亿元」「三年」「血常规」）在大量文档中重复（410 个关键词组中 222 个出现在同领域 10% 以上的文档里，64 个超过 30%），只看文本会把别家公司/别的案件的同类段落也算作命中，使 chunk 层召回虚高。因此 `bm25@50 … fused@20` 与 `context` 层只匹配来自该题 `sourceDocIds` 的片段：

- 页面 → doc_id：评测 KB 页面标题为 `{主体名}-{doc_id}`，每个 run 开始时用目录树与语料 manifest 标题一一对应得到 `pageId → docId`（`EvalCorpusService.pageDocIds`）；
- 基线层：`ChunkHit.resourceType()=="PAGE"` 且 `resourceId` 映射到的 doc_id 属于 `sourceDocIds` 才参与匹配；
- `context` 层：工作流的 `ContextObserved` 除全文 `context`（仅供展示）外，带按证据拆分的 `segments`（子块阶段每个子块一段、父块阶段每个父块正文一段，含 `resourceType`/`resourceId`），按同一规则过滤；
- `answer` 层仍是纯文本匹配；`sourceDocIds` 为空（`source=custom` 自建题）时各层都不过滤；
- 每题每层（answer 除外）记录被排除的非来源片段数 `nonSourceFiltered`（`pool@50` 为两路去重后的数量），写入本地报告逐题明细与 Langfuse 对应层 span 的 output，用于观察同模板干扰的程度。

`bm25@50 … fused@20` 使用原始 query（不改写），直接调用 `ChildRecallPort` 与 `StandardRrfFusion`，衡量检索基线；`context`、`answer` 来自完整工作流，衡量端到端效果。两组差值即改写、图增强、阶梯扩展带来的增益。

负样本单独统计「拒答正确率」：工作流终态为 `insufficient`，或回答文本匹配一组拒答表达正则（`RefusalDetector.DEFAULT_PATTERNS`，覆盖「无法确定/无法计算」「未明确说明/没有提供/未记载」「信息不足」等说法），即为正确；分数备注记录命中片段。2026-10-21 首轮 50q 中模型拒答措辞多样，固定短语表把 4/4 正确拒答误判为未拒答，故改为正则。

### 3.3 首次丢失层归因

对每个非负样本中未在 `answer` 命中的关键词组，按以下顺序判定（先看端到端，再回溯基线）：

1. 在 `context`、不在 `answer` → `GENERATION_MISS`；
2. 不在 `context`，且不在 `pool@50` → `RECALL_MISS`（分词/嵌入/切块导致搜不到）；
3. 不在 `context`，在 `pool@50`、不在 `fused@20` → `RANK_MISS_20`；
4. 不在 `context`，在 `fused@20`、不在 `fused@8` → `RANK_MISS_8`；
5. 不在 `context`，但在 `fused@8` → `CONTEXT_MISS`（阶梯未扩展、父块裁剪或预算截断）。

另记录 `CONTEXT_GAIN`：不在 `pool@50` 但在 `context` 命中（改写/图增强补回）的关键词组数。归因明细写入 trace metadata，并按 `category × 丢失层` 汇总进 run 报告，作为后续改进分析的主要依据。

## 4. 架构

```
Langfuse Cloud ◀── REST(Basic pk:sk) ── infrastructure/langfuse/LangfuseRestClient
                                               ▲ implements
eval/EvalReportPort ◀────────────────── eval/EvalRunService
                                               │
       ┌───────────────────────┬───────────────┼──────────────────────┐
  EvalDatasetLoader      BaselineRetrievalProbe   WorkflowProbe          KeywordMatcher / LossAttributor
  (jsonl / Langfuse)     (ChildRecallPort×2      (AgenticWorkflowPort    (纯函数)
                          + StandardRrfFusion)    + RunObserver)
```

### 4.1 新包 `com.kwiki.eval`

| 单元 | 职责 | 依赖 |
|---|---|---|
| `EvalCase` / `EvalDatasetLoader` | 读取并校验 jsonl；按 tier 过滤 | 无 |
| `KeywordMatcher` | 归一化与关键词组匹配 | 无 |
| `LayerScorer` / `LossAttributor` | 分层计分、首次丢失层归因 | `KeywordMatcher` |
| `BaselineRetrievalProbe` | 原始 query 嵌入一次，调用 BM25、向量两路 Top-50，RRF 取 8/20 | `ChunkEmbeddingPort`、`ConcurrentRecallService`、`StandardRrfFusion` |
| `WorkflowProbe` | 调 `answerInSession(user, query, [], {kbId}, {}, RouteInput.NONE, observer)`，收集回答文本、终态、最终上下文 | `AgenticWorkflowPort`、`RunObserver` |
| `EvalRunService` | 编排一次 run：加锁、逐题执行（有界并发，默认 2）、计分、上报、写本地报告 | 以上全部 + `EvalReportPort`、`KwikiDistributedLocks` |
| `EvalReportPort` | 上报抽象：`upsertDataset`、`upsertItem`、`recordTrace`、`linkRunItem`、`flush` | 无 |
| `EvalAdminController` | `/api/v1/admin/eval/**` 接口 | `EvalRunService` |

### 4.2 运行观测扩展点 `RunObserver`

- 位置：`com.kwiki.rag.orchestration.RunObserver`，事件为不可变 record：`RewriteObserved`（新 query、轮次）、`ChildrenObserved`（轮次、attemptStage、子块 key、是否经图增强）、`ContextObserved`（轮次、attemptStage、生成所用 query、送入生成的上下文全文）。回答文本与终态直接从工作流 SSE 事件（`token`/`done`/`error`）读取，不另设事件。
- 挂载：`RunContext` 新增可选 `observer` 字段（与现有 `thinkingListener` 同模式），默认 `RunObserver.NOOP`；`LangGraphAgenticWorkflow` 在改写完成、子块召回（含图增强后）、生成前拼好上下文处调用 `run.observe(event)`。`context` 层取最后一次 `ContextObserved`。
- 入口：`AgenticWorkflowPort` 新增带 `RunObserver` 参数的 `answerInSession` 默认方法，原有调用方不变。
- 约束：观测者只读；回调异常被捕获并记 WARN 日志，不影响主链路；NOOP 时不构造事件 payload。

### 4.3 Langfuse 客户端 `infrastructure/langfuse`

基于 Spring `RestClient`，使用 Langfuse 公共 API：

- `POST /api/public/datasets`：创建 `kwiki-rag-50q`、`kwiki-rag-100q`、`kwiki-rag-200q`；
- `POST /api/public/dataset-items`：以 `{datasetName}:{case.id}` 作为 item id 幂等 upsert；`input={query, category, domain}`，`expectedOutput={keywords, negative}`，`metadata={source, tier, sourceDocIds, keypoints}`；
- `POST /api/public/ingestion`：批量写 trace、span（每层一个 span，input 为 query，output 为命中/未命中关键词与块 key）与 score；
- `POST /api/public/dataset-run-items`：把 trace 关联到 dataset run。

配置（放 Nacos，代码 `@Value` 给默认值，`application.yml` 不新增）：

- `kwiki.langfuse.host`（默认 `https://cloud.langfuse.com`）
- `kwiki.langfuse.public-key`、`kwiki.langfuse.secret-key`（默认空）
- `kwiki.eval.concurrency`（默认 `2`）
- `kwiki.eval.report-dir`（默认 `./eval-reports`）

未配置 key 时，评测接口返回明确错误（`langfuse-not-configured`），应用正常启动；数据集同步与评测都不可用，但 `?localOnly=true` 允许只跑评测、只写本地报告。

上报的 trace 包含文档片段与回答，会发送到 Langfuse Cloud；评测 KB 仅放公开数据集与用户明确同意的补充文档。密钥不入日志（复用 `SecretRedaction`）。

## 5. 接口

| 方法与路径 | 说明 |
|---|---|
| `POST /api/v1/admin/eval/corpus/import?kbId=&corpus=dragonball-zh` | 导入语料到评测 KB（幂等） |
| `POST /api/v1/admin/eval/datasets/sync` | 将 jsonl 按 tier 同步为三个 Langfuse dataset（幂等） |
| `POST /api/v1/admin/eval/runs` | body `{dataset: "50q"\|"100q"\|"200q", kbId, runName?, notes?, localOnly?}`，异步启动，返回 runId |
| `GET /api/v1/admin/eval/runs/{runId}` | 进度与汇总指标 |

- 全部要求管理员角色；检索范围 = 触发管理员的权限 ∩ `{kbId}`，复用 `AuthorizationScopeResolver`，不新增权限路径。
- 同一时刻只允许一个 run（Redis 分布式锁）。
- `runName` 缺省为 `{dataset}-{yyyyMMdd-HHmm}`；run metadata 记录配置快照（QA 各阶段 TopK、父块上限、图增强是否启用、评测并发）与请求中的自由文本 `notes`（如「ik 分词 + contextual chunk」），便于在 Langfuse 中对比。
- run 状态保存在内存与本地报告文件中，不新增数据库表；应用重启后进行中的 run 视为中断。

## 6. 错误处理

- 单题异常（检索失败、工作流超时、降级）：该题记 `error=1` score 与错误码，继续下一题；汇总中单列错误数，不计入召回分母。
- Langfuse 写入失败：批量指数退避重试 3 次；仍失败则保留在本地报告中，run 状态标记 `report-partial`。
- 评测 KB 尚有索引未完成的页面：启动 run 前检查，存在未完成页面时拒绝启动（`corpus-not-ready`）。
- 生成侧 temperature 为 0.2，回答层存在少量波动；报告注明，必要时同一配置跑两次取均值。

## 7. 本地报告

每个 run 写 `{report-dir}/{runName}.json`：配置快照、各层 coverage/fullHit（总体、按 category、按 domain、按 source）、负样本拒答正确率、丢失层分布矩阵（category × lossLayer）、每题明细（各层命中组、丢失层、错误码、耗时）。后续的召回分析以该报告与 Langfuse 数据为准。

## 8. 测试

- 单元测试：`KeywordMatcher`（NFKC、千分位、别名、空白）、`LayerScorer`、`LossAttributor`（各丢失层与 `CONTEXT_GAIN`）、`EvalDatasetLoader`（tier 嵌套、字段校验、关键词不得出现在题干中）。
- 数据校验测试：jsonl 中每个关键词组至少有一个别名逐字出现在对应语料文档中；三个集合的嵌套与题型比例。
- `LangfuseRestClient`：MockWebServer 校验路径、Basic 认证、请求体与重试。
- `EvalRunService`：fake 召回端口与 fake 工作流端到端跑通，验证单题失败不中断、锁互斥、本地报告内容。
- `RunObserver`：工作流测试中断言事件顺序；NOOP 下不影响现有测试。
- 真实 Langfuse Cloud 冒烟测试只放在 `external-it` profile。

## 9. 交付顺序

1. `RunObserver` 扩展点与工作流埋点；
2. `eval` 核心（加载、匹配、计分、归因）与本地报告；
3. Langfuse 客户端与数据集同步；
4. 语料转换与导入接口；
5. 生成 200 题测试集并通过数据校验；
6. 在评测 KB 上跑 50q/200q 基线，产出召回分析报告与改进建议。
