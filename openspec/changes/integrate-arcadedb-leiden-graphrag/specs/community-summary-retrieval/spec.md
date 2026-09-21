## ADDED Requirements

### Requirement: Bounded evidence-grounded community summaries
系统 SHALL 在离线构建中为社区选择有上限的代表实体、语义关系及来源 Chunk，并生成 title、summary、keywords、核心概念、重要关系、问题类型及 sourceRefs。每条可用于回答的摘要断言 MUST 有输入来源支撑；超大社区 MUST NOT 以全量实体/边作为无界模型输入。

#### Scenario: Community exceeds summary input budget
- **WHEN** 社区实体与关系数超过已配置的代表集合或字符预算
- **THEN** 系统按稳定排序选取有界代表子集，记录输入来源和截断，不加载全社区到模型上下文

#### Scenario: Summary refers to an unknown entity or evidence
- **WHEN** 模型输出引用输入集合之外的实体或 sourceRef
- **THEN** 校验拒绝该摘要并按有界重试策略处理，不写入可发布社区索引

### Requirement: Independent versioned community search index
社区摘要 SHALL 保存到与 Chunk 分离的 ES 物理索引，文档 MUST 包含完整社区身份、communityIndexVersion、来源清单、内容/安全 epoch、Chunk 流水线身份、摘要模型/prompt 和 embedding 身份。社区 ES 版本 SHALL 独立于 chunkIndexVersion、graphVersion 和 mappingSchemaVersion，由服务端按构建批次分配；物理索引 SHALL 按社区版本及知识库隔离。title、summary、keywords SHALL 支持 BM25，vector SHALL 支持维度固定的向量召回；所有数据 MUST 在发布前刷新并验证可读。

#### Scenario: Batch builds communities for multiple knowledge bases
- **WHEN** 全量批次分配 communityIndexVersion=7 并包含知识库 A 和 B
- **THEN** 两个子任务均标记 COMMUNITY v7，各自创建该版本下独立物理索引，并分别绑定各库图快照，不复用一个模糊 version 字段

#### Scenario: Embedding has an unexpected dimension
- **WHEN** 某摘要向量维度与该快照 mapping 或模型配置不符
- **THEN** 写入/校验失败且阻断发布，不用截断或补零伪造有效向量

#### Scenario: Community and chunk models differ
- **WHEN** 社区索引的 embedding 模型或维度不同于 Chunk 索引
- **THEN** 系统分别计算匹配的查询向量，不按查询文本相同就复用另一模型的向量

### Requirement: Full-source authorization and selected-scope gate before ranking
社区 SHALL 按知识库全部有效来源构建，不按用户拆分。首版系统 MUST 在社区召回 TopK 前证明用户可读该库全部当前有效可索引来源及整个快照清单、请求所选范围覆盖完整清单、快照 epoch 当前有效且 Chunk 流水线兼容。权限或所选范围不能证明完整时，该知识库 MUST 关闭社区搜索、摘要和所有图扩展，沿用既有 Chunk/QA 流程；MUST NOT 输出其摘要、标题、成员约束、代表实体或统计。仅校验 kbId 或代表来源不足以通过门禁。

#### Scenario: Member cannot read a private source page
- **WHEN** 用户属于知识库但该快照含其无权读取的 PRIVATE 页面
- **THEN** 该库的社区索引不参与排名，也不执行局部图或 seed-only 扩展；用户沿用可见 Chunk 的既有检索和 QA 流程

#### Scenario: User can read everything but selects one page
- **WHEN** 用户有全库权限，但本次请求只选择其中一页
- **THEN** 包含范围外来源的社区不可用于本次请求，不因权限充足而扩大用户选定范围

#### Scenario: Hidden evidence affects community membership
- **WHEN** 摘要的代表 Chunk 均可见，但社区划分还依赖不可见来源
- **THEN** 仍拒绝共享社区路径，不把代表 Chunk 可见等同于整个派生社区可见

#### Scenario: A new private document is absent from the old snapshot
- **WHEN** 当前知识库新增了用户不可读的有效文档，即使旧快照尚未包含它
- **THEN** 当前全来源授权检查关闭该库图增强，不仅检查旧快照清单就放行

#### Scenario: Only one knowledge base in a multi-base request passes the gate
- **WHEN** 请求涉及 A、B，用户仅对 A 有完整来源权限与选择范围
- **THEN** A 可以执行图增强，B 仅执行原有授权 Chunk 检索，不因 A 合格而向 B 扩散图查询

### Requirement: Bounded hybrid community ranking and graceful degradation
社区搜索 SHALL 并行执行授权范围内 BM25 与向量分支，按 RRF k=60 融合并执行全请求社区上限。相同 query/model/dimensions 的 embedding SHALL 仅在当前请求内复用；不同版本社区 MUST 以完整身份去重。社区失败 MUST 保留可用 Chunk 分支，且区分失败、禁用、失效和成功零命中。

#### Scenario: Several knowledge bases produce candidates
- **WHEN** 多个允许搜索的快照各自产生社区命中
- **THEN** 最终社区数不超过全请求上限，不能按知识库数量倍增图预算

#### Scenario: Community vector branch fails
- **WHEN** 社区向量计算或向量召回失败而 BM25 成功
- **THEN** 系统使用 BM25 社区候选并记录部分降级，Chunk 检索继续

### Requirement: Summary invalidation and original-source citation
来源内容/安全 epoch 变化 MUST 立即取消旧摘要资格，重新构建后才恢复。摘要 SHALL 仅作辅助上下文，MUST NOT 在没有当前有效原文时独立回答或生成 Community 原文引用；输出前 MUST 复核授权和生命周期。

#### Scenario: Summary exists but all supporting chunks are invalid
- **WHEN** 社区命中但没有任何当前可读且属于选择范围的原文证据
- **THEN** 系统不把摘要当作可回答证据，进入既有无证据恢复流程

#### Scenario: Permission is revoked during streaming
- **WHEN** 检索完成后用户对来源文档失去权限
- **THEN** 后续摘要、图内容和引用停止输出，不从缓存继续发送失权内容
