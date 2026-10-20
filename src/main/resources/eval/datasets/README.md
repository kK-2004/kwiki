# kwiki RAG 召回评测测试集

- `kwiki-rag.jsonl`：唯一事实来源，200 题；`tier` 为首次纳入层级，50q ⊂ 100q ⊂ 200q。
- `dragonball-sample.json`：抽样中间产物（含原始 references/answer），用于审阅关键词。
- 来源与许可：见 `../corpus/dragonball-zh/NOTICE.md`（CC BY-NC-SA 4.0，仅内部评测）。

## 复现抽样

    node tools/eval/sample-dragonball.mjs <dragonball_queries.jsonl> src/main/resources/eval/datasets/dragonball-sample.json

固定种子 20261020；排除引用 doc 4 / doc 24（同名公司）的题与非负样本中 references 为空的题。
题型配额（tier 50/100/200 累计）：无关无解问 4/8/16，事实性问题 10/21/42，多跳推理问题 8/15/30，
总结性问题 9/17/35，多文档信息整合问题 10/21/42，多文档对比问题 6/11/22，多文档时间序列问题 3/7/13。

## 关键词规则

每题 1~6 个关键词组，组内为别名；必须能在来源文档原文找到，且不得出现在题干中；多文档题每篇来源至少一组。
`EvalDatasetFileTest` 会校验以上全部规则。

关键词由 `tools/eval/build-kwiki-rag.mjs` 生成：先从标准答案与 references 自动抽取（日期、数字+单位、
中文数词、量刑短语优先，短语挖掘兜底），再用 `keyword-overrides.json` 按 id 整体覆盖，最后执行与
`EvalDatasetFileTest` 相同的门禁。重跑命令：

    node tools/eval/build-kwiki-rag.mjs src/main/resources/eval/datasets/dragonball-sample.json \
        src/main/resources/eval/corpus/dragonball-zh src/main/resources/eval/datasets/kwiki-rag.jsonl

不要手改 `kwiki-rag.jsonl`，也不要为个别题去调脚本启发式；需要修正某题关键词时改覆盖文件后重跑。

## 关键词覆盖 `keyword-overrides.json`

自动抽取只认得日期和数字，对总结/多跳/多文档题常产出截断短语（如「提高成本控制水平和优化资」），
或按日期优先占满 6 组而挤掉答案真正的金额。覆盖文件记录人工审阅后的关键词，逐题审阅记录见
`docs/rag-eval-keyword-review.md`。

- 格式：`{"<id>": {"keywords": [["别名", ...], ...], "reason": "为何覆盖"}}`；以下划线开头的键是说明，脚本忽略。
- 覆盖是整体替换该题全部关键词组，`reason` 必填；id 必须存在且不能是负样本。
- 关键词代表「回答该题必需的信息」：答案核心值（合计金额、结论里的数字/名称）优先，不因 6 组上限被挤掉；
  短语取完整词语、2~10 字，优先能区分本题的实体（措施名、诊断名、人名、公司名），避开同类文档共有的套话。
- 推理得出、原文没有的值（如「共 9 项」「合计 40 万元」）不作关键词，用其组成项代替。
- 覆盖后的关键词同样要满足上面的全部关键词规则，脚本不满足时直接失败、不写文件。

## 追加自建题

自建文档（PDF/多模态）上传到同一评测知识库后，按相同格式追加行，`source` 设为 `custom`、`sourceDocIds` 置空，
并确保关键词能在文档解析后的文本中找到。自建题不参与 DragonBall 配额校验时，需同步调整 `EvalDatasetFileTest`。
