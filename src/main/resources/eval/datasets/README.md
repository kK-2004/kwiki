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

关键词由 `tools/eval/build-kwiki-rag.mjs` 从标准答案与 references 抽取生成（日期、数字+单位、
中文数词、量刑短语优先，短语挖掘兜底），重跑命令：

    node tools/eval/build-kwiki-rag.mjs src/main/resources/eval/datasets/dragonball-sample.json \
        src/main/resources/eval/corpus/dragonball-zh src/main/resources/eval/datasets/kwiki-rag.jsonl

## 追加自建题

自建文档（PDF/多模态）上传到同一评测知识库后，按相同格式追加行，`source` 设为 `custom`、`sourceDocIds` 置空，
并确保关键词能在文档解析后的文本中找到。自建题不参与 DragonBall 配额校验时，需同步调整 `EvalDatasetFileTest`。
