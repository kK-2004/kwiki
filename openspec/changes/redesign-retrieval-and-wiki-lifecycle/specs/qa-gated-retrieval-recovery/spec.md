## ADDED Requirements

### Requirement: Independent hybrid child retrieval and bounded RRF
系统 MUST 对每个当前 query 执行独立的 ES BM25 与向量 CHILD 召回，在各分支 TopK 之前应用相同的服务端权限、ACTIVE 生命周期和修订过滤。系统 SHALL 使用一基排名和常数 60 的标准 RRF，按 chunkKey 去重后取当前最终子 TopK；每次检索独立融合，不累加旧 query 或旧 TopK 的排名。

#### Scenario: Both branches hit the same child
- **WHEN** 一个子 chunk 在关键词排名第 5、向量排名第 2
- **THEN** 其融合分数为 1/65 + 1/62，最终候选只出现一次，并保留两路排名来源

#### Scenario: First retrieval uses base limits
- **WHEN** 首轮原始 query 被路由为知识检索
- **THEN** 系统不先语义改写，各分支最多 20 个子 chunks，RRF 后最多 8 个子 chunks，初次生成不获取父正文

#### Scenario: Archived or unauthorized chunk ranks highest
- **WHEN** 已归档、过期修订或越权 chunk 本来匹配度最高
- **THEN** 它不占用任一分支候选位置，也不进入融合、生成上下文或引用

#### Scenario: Ranking is recomputed after expansion
- **WHEN** 同一 query 的分支候选从 20 扩到 50，融合 TopK 从 8 扩到 20
- **THEN** 使用新的两个排名重新 RRF，旧两个排名不再贡献分数，实际保留数量和上下文截断分别记录

### Requirement: Candidate answers are evaluated before publication
系统 MUST 先生成有界候选回答，再让 QA 同时评估原始问题、当前 query、候选正文和实际证据。默认阈值 SHALL 为 0.80，relevance/coverage/faithfulness 三项均须达到阈值，且 passed=true、无 unsupportedClaims、支持证据和引用合法才允许发布。系统 MUST NOT 将 RRF 当作质量概率。

#### Scenario: Child candidate passes the gate
- **WHEN** 子 chunks 候选满足全部门控条件
- **THEN** 系统逐段发布该候选原文及其有效引用，不获取父正文、不再调用生成模型，随后输出唯一 done

#### Scenario: Candidate fails QA
- **WHEN** QA 发现候选覆盖不足或存在无依据陈述
- **THEN** 候选 token 和引用不会发送给客户端或写入最终回答，系统保存结构化拒绝原因并进入规定的下一补救阶段

#### Scenario: High score contains invented citations
- **WHEN** QA 分数均达到 0.80 但支持 ID 不属于本次 retainedEvidence
- **THEN** 服务端拒绝该无效 QA 结果，候选不能被发布

#### Scenario: Evidence changes before output
- **WHEN** QA 已通过但输出前资源被归档或权限/修订发生失效变化
- **THEN** 系统重新验证并阻止使用失效内容，返回明确的资源或权限变更终态

### Requirement: Recovery follows child parent expansion rewrite order
系统 SHALL 对每个 query 按基础子候选 → 基础父候选 → 扩大子候选 → 扩大父候选依次补救，各次候选都重新生成并 QA，任何一次通过立即停止。父证据 MUST 仅回取当前保留子 chunks 的父节点、按首次子命中顺序去重并受权限与上下文预算约束；每个 query 只扩大一次。

#### Scenario: Parent context fixes a rejected child answer
- **WHEN** 基础子候选未通过而其父上下文生成的候选通过
- **THEN** 发布父候选，轨迹包含父回取/重新生成/QA，但不包含扩大检索或改写

#### Scenario: Base parent candidate is rejected
- **WHEN** 基础父候选仍不通过
- **THEN** 同一 query 重新调用两路 ES，各分支 50、最终 TopK 20；新子候选失败才回取新父候选，最多 16 个父 chunks

#### Scenario: Context exceeds its budget
- **WHEN** 基础子、扩大子或父正文超过各自 12000、24000、24000 字符预算
- **THEN** 系统按排名确定性约束上下文，仅为实际保留内容提供引用，并记录截断而不无界扩大 prompt

#### Scenario: Retrieval succeeds with zero hits or parents add nothing
- **WHEN** 两路都成功零命中，或父内容不可用/与已用上下文相同
- **THEN** 系统记录 no-evidence 或 skipped，不空跑相同候选，按顺序进入扩大或改写，不把 skipped 显示为成功执行

### Requirement: Feedback rewrite preserves original query and complete history
系统 MUST 使用 Query Rewrite Agent 进行失败反馈改写，输入包含不可变 originalQuery、lastQuery、所有 previousRewrites、各阶段 QA 拒绝原因与缺失项、已回取父 chunks、已尝试扩大 TopK 的事实及前后数值。系统 SHALL 用结构化数据边界隔离 query、文档和拒绝理由中的指令，且每次最多返回一个不同于历史的新 query。

#### Scenario: First recovery rewrite
- **WHEN** 原始 query 的扩大父候选仍不通过
- **THEN** Agent 收到原始问题、空历史改写、上轮实际 query、QA 理由及 8→20 TopK 已扩大事实，成功改写开启基础 TopK 的第 2 query 轮

#### Scenario: Second recovery rewrite
- **WHEN** 第 2 query 轮仍失败
- **THEN** Agent 同时收到原始问题、此前改写、上轮 query、该轮所有拒绝原因与扩大事实，不把上轮改写当成新的原始问题

#### Scenario: Rewrite repeats prior query
- **WHEN** Agent 输出空白、无效或重复原始/历史 query
- **THEN** 本次调用计入最多 2 次改写预算，不重发相同检索，有预算时带校验失败原因再调用，否则正常拒答

### Requirement: Global iteration limits and explicit outcomes
系统 SHALL 默认最多 3 个 query 轮次（原始 + 2 改写）、6 次双路检索、6 次父回取、12 次生成、12 次 QA。总模型/工具/步骤上限 SHALL 为 32/12/128，总时限 300s，单次模型 30s、ES 分支 5s，候选最多 32000 字符；所有并发和异步工作共享取消与预算。

#### Scenario: Every candidate fails quality
- **WHEN** 3 个 query 轮次的全部可用补救仍不能通过 QA
- **THEN** 系统只输出「知识库中没有相关信息，我无法进行回答」，无引用、outcome=insufficient，不再调用模型或检索

#### Scenario: Worst valid quality path fits counters
- **WHEN** 路由调用 1 次、改写调用 2 次、12 次生成与 12 次 QA 全部完成且未超时
- **THEN** 27 次对话模型调用可落在配置上限内，不因旧 16 次限制提前中断第三轮

#### Scenario: Infrastructure fails
- **WHEN** 两路召回均失败、QA/改写服务不可用、生成失败或达到总超时
- **THEN** 系统报告相应错误而非知识库无信息，停止下游调用且不输出未评审答案

#### Scenario: Only one retrieval branch fails
- **WHEN** 一路返回有效结果而另一路失败
- **THEN** 系统可以对成功分支继续融合和门控，但必须标记具体分支 degraded，不能显示双路成功

#### Scenario: User cancels during candidate generation
- **WHEN** 客户端取消当前 run
- **THEN** 模型/检索/QA 后续工作终止，取消后不补发 token、引用或正常完成事件
