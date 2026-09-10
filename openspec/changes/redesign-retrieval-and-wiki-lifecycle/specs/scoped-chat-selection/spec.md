## ADDED Requirements

### Requirement: Chat retrieval can be narrowed to selected knowledge bases and Wikis
系统 SHALL 提供知识库/Wiki 三列多选器。空选择表示全部已授权内容；非空选择 MUST 在服务端与实时授权范围求交，并贯穿关键词、向量、父块回取和引用校验。

#### Scenario: User selects several Wikis
- **WHEN** 用户选择多个知识库或 Wiki 后提问
- **THEN** 两路检索仅返回选择范围与授权范围交集中的 chunks

#### Scenario: Client submits unauthorized IDs
- **WHEN** 请求伪造无权限知识库或页面 ID
- **THEN** 服务端丢弃该范围且不得扩大访问权限
