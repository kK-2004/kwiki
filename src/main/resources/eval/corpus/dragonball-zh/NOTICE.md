# DragonBall 评测语料声明

本目录文档转换自 OpenBMB/RAGEval 发布的 DragonBall 数据集中文部分：
https://github.com/OpenBMB/RAGEval （dragonball_dataset/dragonball_docs.jsonl）

论文：Zhu et al., "RAGEval: Scenario Specific RAG Evaluation Dataset Generation Framework", ACL 2025.

许可：Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International（CC BY-NC-SA 4.0）。
本目录及 `src/main/resources/eval/datasets/` 中由其衍生的测试集沿用同一许可，仅用于 kwiki 内部检索评测，不得用于商业分发。

转换方式：仅取 `language=zh` 的文档，正文保持原样，每篇保存为 `{domain}-{doc_id}.md`；页面标题为 `{主体名}-{doc_id}`，见 `manifest.json`。
