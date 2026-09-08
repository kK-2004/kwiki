## ADDED Requirements

### Requirement: Compact shared activity disclosure
系统 SHALL 在侧面板和完整会话页共用同一活动组件，助手回答上方默认仅显示可折叠的一行状态，展开后使用细时间线和真实业务步骤。组件 MUST 保留用户折叠状态，支持键盘和 aria-expanded，状态不能只依赖颜色。

#### Scenario: User opens a retrieval answer
- **WHEN** 新问答开始并进入知识检索
- **THEN** 显示「正在检索知识库」等业务状态，默认折叠，不展示 started/route/rewrite 原始枚举或原型 demo 区域

#### Scenario: User expands while generation is running
- **WHEN** 用户展开活动后收到新事件
- **THEN** 内容持续更新但不自动折叠或抢占滚动；两个问答入口显示同样的阶段含义

#### Scenario: Request does not retrieve
- **WHEN** 请求直接问候回复或需要澄清
- **THEN** 不显示「已检索知识库」或虚构检索时间线

### Requirement: Timeline reflects actual stages and counts
系统 MUST 展示实际路由、两条检索分支、RRF/TopK、预生成、QA、父回取、扩检与改写事件，并按 queryRound/attemptStage 分组。摘要中的数量和耗时 SHALL 来自真实数据，区分分支 hits、融合候选、保留子 chunks 和父 chunks；来源最多显示 4 个已授权标题。

#### Scenario: Both branches complete
- **WHEN** 关键词/向量召回结束并完成 RRF
- **THEN** 展开态分别显示两路状态/耗时/命中数，随后显示融合保留 TopK，不把两个列表叫作两个 chunks 或混成父文档数

#### Scenario: Recovery is necessary
- **WHEN** QA 不通过后依次发生父回取、扩大 TopK 和 query 改写
- **THEN** 时间线按实际顺序追加，QA 使用简短业务原因，显示新轮次而非覆盖之前失败步骤

#### Scenario: Data is missing or a branch degrades
- **WHEN** 事件没有耗时/计数，或只有一条分支成功
- **THEN** 缺失字段不造假，失败分支清楚标记降级，不能显示统一成功勾选

#### Scenario: Narrow screen and long query
- **WHEN** 用户在窄屏查看很长的 query 与来源
- **THEN** 保持布局可用、次要统计可以隐藏、query 可展开读取且来源仍可访问

### Requirement: Versioned idempotent activity persistence
系统 SHALL 使用新增 versioned activity SSE，含 requestId、seq、stepId、parentStepId、queryRound、attemptStage、phase、status、时间、摘要和语义明确的 metrics；用 stepId 合并开始/完成、seq 去重并绑定助手消息持久化。既有 token/citations/done/error 与旧历史 MUST 保持可读。

#### Scenario: Duplicate or delayed event arrives
- **WHEN** 已完成步骤之后收到重复 complete 或较旧 started
- **THEN** reducer 不重复加步骤、不重复计数、不把已完成状态改回 running

#### Scenario: Conversation is reopened
- **WHEN** 用户离开后重新打开已完成会话
- **THEN** 该助手消息重放同一活动及终态，旧协议历史使用标签映射且缺失统计不编造

#### Scenario: QA rejects an internal candidate
- **WHEN** 前端收到 quality/retry 活动
- **THEN** 它不包含未通过候选正文、prompt、模型隐藏推理、原始内部工具调用或密钥
