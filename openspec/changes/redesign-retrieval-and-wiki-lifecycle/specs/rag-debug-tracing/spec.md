## ADDED Requirements

### Requirement: Correlated debug events span the entire query lifecycle
系统 SHALL 在 DEBUG 记录用户 query → 意图路由 → 两路召回 → RRF/TopK → 预生成 → QA → 父回取/扩大/改写 → 终态的 start/complete/skipped/error，携带 traceId、requestId、runId、sessionId、stageId、queryRound、attemptStage、耗时、状态及预算。异步线程和模型回调 MUST 正确绑定并清理关联上下文。

#### Scenario: Request traverses multiple recovery stages
- **WHEN** 请求从子候选失败进入父补充、扩检和改写
- **THEN** 按同一 requestId 可查到全部实际阶段与下一动作原因，不因线程切换断链

#### Scenario: Concurrent requests complete or cancel
- **WHEN** 多个 query 同时执行且其中一个取消
- **THEN** 日志不混用请求标识，取消有独立终态，后续不出现该请求新的未授权阶段

### Requirement: Debug payload explains inputs outputs and gate decisions
系统 MUST 记录脱敏原始/current query、全部历史改写、路由结果、分支候选上限/命中/排名、RRF 分数与保留数、证据级别、候选 ID/字符数/受限摘要、QA 阈值/评分/拒绝原因/缺失项、下一动作与实际终态。RRF 与 QA 分数 SHALL 使用不同字段，完整内部模型推理不作为业务诊断内容。

#### Scenario: Query rewrite is diagnosed
- **WHEN** DEBUG 记录 Query Rewrite Agent 调用
- **THEN** 能确认原始 query、上一轮 query、全部历史改写、拒绝原因和 TopK 扩大事实都已传入，输出记录实际新 query 和预算消耗

#### Scenario: QA rejects generated content
- **WHEN** 一个候选未通过门控
- **THEN** 日志能关联候选 ID、摘要、所用证据、阈值和拒绝维度，浏览器活动仅收到简短业务原因

### Requirement: Debug logging is bounded and redacted
系统 MUST 复用密钥脱敏，禁止日志包含 token、Authorization、密码、完整签名链接、向量数组和完整证据。query/原因按业务长度限额，候选摘要默认最多 2000 字符，截断项附 originalLength/truncated；日志截断不得改变实际模型输入。关闭 DEBUG 时 SHALL 避免构造昂贵 payload。

#### Scenario: Sensitive values occur inside query or an SDK error
- **WHEN** query、反馈或异常中包含 token/签名 URL/凭据
- **THEN** 输出统一脱敏，仍能通过 requestId、错误分类与阶段诊断问题

#### Scenario: Debug is disabled or payload is long
- **WHEN** 生产日志级别高于 DEBUG，或候选摘要超长
- **THEN** 前者不构造大对象日志，后者确定性截断并标记长度，既不改变实际 query/回答也不省略阶段标识
