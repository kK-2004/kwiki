## ADDED Requirements

### Requirement: 按用途独立分配模型
系统 SHALL 为 `AGENT_PLANNER`、`RETRIEVAL_PLANNER`、`ROUTER`、`REWRITE`、`DIRECT_ANSWER`、`ANSWER`、`SELECTION_QUESTION`、`QUALITY_ANALYZER`、`GRAPH_EXTRACTION`、`GRAPH_SUMMARY`、`VISION_SUMMARY` 分别维护一个模型分配，以及可选的 temperature、maxTokens、thinking；参数为空时 SHALL 使用该用途的代码默认值。

#### Scenario: planner 与回答使用不同模型
- **WHEN** 管理员把 `AGENT_PLANNER` 分配给 `qwen-turbo`、把 `ANSWER` 分配给 `qwen-max`
- **THEN** 下一次问答中 planner 调用 `qwen-turbo`，回答流式调用 `qwen-max`

#### Scenario: 同一模型用于不同用途时参数不同
- **WHEN** `ROUTER` 与 `ANSWER` 都分配给同一个模型，未填参数
- **THEN** 路由调用使用 temperature 0.0，回答调用使用 temperature 0.2 并开启 thinking

### Requirement: 分配时校验标签与能力
保存分配时系统 SHALL 校验：所选模型及其 provider 均已启用；模型带有该用途要求的标签；`tool-mode=native` 时 planner 类用途要求 `supports_tools`；开启 thinking 时要求 `supports_thinking`。任一校验失败时 SHALL 拒绝整批保存。

#### Scenario: 把 embedding 模型分配给路由
- **WHEN** 管理员把只有 `embedding` 标签的模型分配给 `ROUTER`
- **THEN** 接口返回 400，并指出该用途需要 `chat` 标签，所有分配保持不变

### Requirement: 未配置时显式失败
当用途未分配模型或所分配的模型不可用时，系统 SHALL 抛出 `ModelNotConfiguredException`，MUST NOT 静默改用其他模型。

#### Scenario: 图片摘要未分配 vision 模型
- **WHEN** `VISION_SUMMARY` 未分配而多模态任务需要生成图片摘要
- **THEN** 该任务失败，并在后台显示「VISION_SUMMARY 未配置模型」
