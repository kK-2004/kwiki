# RAG 召回评测运行说明

基于关键词的分层召回评测：DragonBall 中文语料（108 篇）+ 200 题嵌套测试集（50q ⊂ 100q ⊂ 200q），
结果写入 Langfuse Cloud 并在本地落 JSON 报告。设计见
`docs/superpowers/specs/2026-10-20-langfuse-rag-eval-design.md`。

## 1. 配置（Nacos 应用配置）

| 键 | 默认 | 说明 |
|---|---|---|
| `kwiki.langfuse.host` | `https://cloud.langfuse.com` | 美区为 `https://us.cloud.langfuse.com` |
| `kwiki.langfuse.public-key` | 空 | Langfuse 项目 Public Key |
| `kwiki.langfuse.secret-key` | 空 | Langfuse 项目 Secret Key（勿入仓库） |
| `kwiki.eval.concurrency` | `2` | 同时评测的题数（1~8），受模型限流约束 |
| `kwiki.eval.report-dir` | `./eval-reports` | 本地报告目录 |
| `kwiki.eval.refusal-phrases` | `无法回答,信息不足,未找到相关,没有相关信息` | 负样本拒答判定短语 |

未配置 key 时应用正常启动；评测只能以 `localOnly=true` 运行。上报的 trace 含文档片段与回答，
评测知识库只放公开数据集与明确同意上传的文档。

## 2. 步骤（均需管理员令牌）

1. 新建一个专用知识库（例如「RAG 评测」），记下 `kbId`。
2. 导入语料：`POST /api/v1/admin/eval/corpus/import?kbId={kbId}&corpus=dragonball-zh`（幂等，可重复调用）。
3. 等待索引完成：评测启动时会检查，未就绪返回 `corpus-not-ready`。
4. 同步测试集到 Langfuse：`POST /api/v1/admin/eval/datasets/sync`，生成 `kwiki-rag-50q/100q/200q`。
5. 启动评测：`POST /api/v1/admin/eval/runs`，body：
   `{"dataset":"50q","kbId":123,"runName":"baseline-50q","notes":"现状基线","localOnly":false}`
6. 查看进度：`GET /api/v1/admin/eval/runs/{runId}`；完成后在 Langfuse 的 Datasets → Runs 中对比。

## 3. 指标

- 每层两个指标：`coverage@{层}`（关键词覆盖率均值）、`fullHit@{层}`（全部关键词命中的题占比）。
- 层：`bm25@50`、`vector@50`、`pool@50`、`fused@8`、`fused@20`（原始 query 基线），`context`、`answer`（完整工作流）。
- 首次丢失层（trace metadata `losses` 与报告 `lossMatrix`）：
  `GENERATION_MISS`（上下文有、回答无）、`RECALL_MISS`（两路 Top-50 都没有）、`RANK_MISS_20`、`RANK_MISS_8`、`CONTEXT_MISS`（在 Top-8 却没进最终上下文）。
- `contextGains`：基线候选池没有、但改写/图增强后进入上下文的关键词组数。
- 负样本 `refusal`：终态 insufficient 或回答含拒答短语记 1。
- 回答层受生成温度（0.2）影响有少量波动，结论性对比建议同配置跑两次。

## 4. 对比改进

每次检索改动后用相同 dataset 跑一次，`notes` 写清改动（如「ik 分词」「contextual chunk」「rerank」），
在 Langfuse 中按 run 对比各层分数，用本地报告的 `lossMatrix` 判断丢失集中在哪一层。

## 5. 维护测试集

见 `src/main/resources/eval/datasets/README.md`。修改后运行 `./mvnw -q -Dtest=EvalDatasetFileTest test`，
再调用一次数据集同步。
