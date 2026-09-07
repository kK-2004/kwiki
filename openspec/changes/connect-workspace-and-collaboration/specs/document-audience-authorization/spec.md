## ADDED Requirements

### Requirement: Document-specific audience and management inheritance
文档 SHALL 支持 PRIVATE、SELECTED_MEMBERS 和历史兼容 KB_MEMBERS；新上传仅提供前两项。文档创作者/管理员、所属知识库 OWNER/ADMIN 与平台超管 SHALL 具有管理继承例外，普通成员 MUST NOT 因属于知识库而读取 PRIVATE/未选中的文档。

#### Scenario: Private document is accessed by a regular member and an administrator
- **WHEN** 所属知识库普通成员和管理员分别请求 PRIVATE 文档
- **THEN** 普通成员被拒绝，管理员有管理权限，界面在仅自己选项说明管理例外

### Requirement: Searchable multi-knowledge-base member selection
受众选择窗口 SHALL 左侧展示有权浏览成员的知识库树、右侧展示所选知识库的分页用户名搜索和多选人员，支持保留、添加、移除跨库选择；提交 SHALL 重新验证候选资格。

#### Scenario: User selects people across two knowledge bases
- **WHEN** 用户搜索并勾选库 A 的成员、切到库 B 添加成员再移除 A 的一人
- **THEN** 最终仅授予所选人员文档可读权限，重复用户去重展示，两个知识库的成员关系不发生变更

#### Scenario: No people selected or a selected member leaves
- **WHEN** 用户只选择知识库节点而未选人，或提交前所选成员已退出来源库
- **THEN** 不把该库全员授权；提交报告需修正的失效候选，不能扩大受众

### Requirement: Explicit grants and source membership lifecycle
受众 SHALL 为关联来源知识库的显式成员快照；新增来源库成员不自动获得权限，退出来源库使对应授权失效。文档直接邀请授权 SHALL 独立于知识库成员，多个有效授权取最高角色。

#### Scenario: Shared reader is outside the owning knowledge base
- **WHEN** 用户通过指定成员或文档邀请获得一页权限
- **THEN** 可以从共享空间打开该页，但不能读取整个归属知识库、兄弟页面或不可见祖先标题

#### Scenario: Owner changes audience to private
- **WHEN** 创作者确认把文档切换到 PRIVATE
- **THEN** 普通受众、直接成员授权及未消费邀请/申请一并撤销，保留管理例外，范围版本推进

### Requirement: Complete server-enforced document boundary
系统 SHALL 对树/列表/搜索、正文/修订/草稿、来源/附件/摘要/下载、统计/评论/@、通知、RAG 检索/上下文/引用和聊天历史统一执行文档授权。授权变更 SHALL 更新持久化范围版本并使旧范围缓存失效。

#### Scenario: Restricted page matches a search or RAG query
- **WHEN** 未授权用户的查询命中受限页索引
- **THEN** 页面内容、标题、统计、引用和来源均不进入响应或模型上下文，不能只凭 kbId 放行

#### Scenario: Permission changes during a stream
- **WHEN** 用户在流式检索/回答期间被移除文档权限
- **THEN** 后续出站上下文/引用复核发现版本变化并停止，不继续发送失权内容
