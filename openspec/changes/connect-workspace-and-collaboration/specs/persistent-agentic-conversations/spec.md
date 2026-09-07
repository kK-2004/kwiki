## ADDED Requirements

### Requirement: Owned persistent conversations
系统 SHALL 提供本人会话创建、分页列表、历史、重命名、逻辑删除和续聊，使用 MySQL chat_session/chat_message/run 持久化，生成上下文 SHALL 使用授权且有界的历史。

#### Scenario: User continues a previous conversation
- **WHEN** 用户重新打开自己的已保存会话并发送问题
- **THEN** 展示历史并在同一 sessionId 追加 turn，编排器使用允许的历史上下文

#### Scenario: Foreign conversation or inaccessible historical evidence
- **WHEN** 用户请求他人 sessionId，或自己的历史引用已经失权
- **THEN** 拒绝他人会话访问；失权证据和无法安全剥离的助手消息被隐藏，不能送回模型

### Requirement: Single persistent run per submitted turn
发送 SHALL 在调用模型前持久化用户消息与 run，使用 clientMessageId 幂等去重，同会话同时最多一个 active run。完成、失败、取消、中断 SHALL 可区分持久化。

#### Scenario: Submit is retried or a second concurrent turn starts
- **WHEN** 相同 clientMessageId 被重试或当前 run 未结束又提交新 turn
- **THEN** 不重复创建消息/模型调用；相同请求返回关联状态，新并发 turn 返回冲突

#### Scenario: Process or persistence fails
- **WHEN** 服务在生成中重启，或终态业务消息保存失败
- **THEN** 前者恢复为 INTERRUPTED，后者显示保存失败的可恢复错误，不声称历史已成功保存

### Requirement: Floating chat and full-page continuity
新聊天 SHALL 先允许输入，首次发送后成为右下角浮窗；“-” SHALL 最小化为入口按钮，最大化 SHALL 到会话页继续同一会话。两种视图 SHALL 共用 store 和流连接。

#### Scenario: User minimizes and maximizes during streaming
- **WHEN** token 生成时用户最小化、恢复、再最大化
- **THEN** sessionId、run、消息及生成进度保持，只有一条流请求，页面挂载不重复发送 query

#### Scenario: User explicitly stops or reloads
- **WHEN** 用户停止/退出/删除当前会话，或浏览器刷新导致连接断开
- **THEN** 前者取消全局运行，后者按历史显示完成或中断状态；不自动重放 turn

### Requirement: Stateful tool and quality progress
SSE SHALL 保留现有事件与扁平 seq/requestId 契约，补会话/run 关联和可公开进度。工具链默认 SHALL 显示状态+摘要、query 改写、混合检索、证据摘要/实际分数与类型、QA 通过或不通过原因和重试。

#### Scenario: Retrieval completes and QA rejects evidence
- **WHEN** 混合检索成功而 QA 不通过并触发下一轮
- **THEN** 同一调用条目更新成功摘要和实际分数，显示 QA 原因及重试轮次，run-wide seq 不重置

#### Scenario: Confidence is absent or a late event arrives
- **WHEN** 服务未返回置信分、发送未知非终态事件或终态后发来 token
- **THEN** 分别显示未提供、忽略未知事件、忽略终态后的修改，不伪造分数和完成状态

### Requirement: Expandable public reasoning summaries without scroll contention
思考区域 SHALL 仅显示可公开的流式执行解释，折叠为最新一行、可展开滚动查看完整公开内容。系统 MUST NOT 透传隐藏推理、系统提示或未授权原始证据。消息区与解释区 SHALL 独立控制自动跟随。

#### Scenario: User reads earlier explanation while generation continues
- **WHEN** 用户展开解释并上滚离开底部后新内容到达
- **THEN** scrollTop 保持用户位置，显示新内容提示；仅主动回底/点击回底才恢复跟随，外层消息区不抢内层滚动

#### Scenario: Explanation is collapsed
- **WHEN** 解释流持续追加
- **THEN** 折叠区展示最新一行，展开后可查看本次已接收的完整可公开解释
