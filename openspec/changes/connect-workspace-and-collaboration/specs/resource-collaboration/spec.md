## ADDED Requirements

### Requirement: Expiring resource-scoped invitation links
知识库和文档 SHALL 支持复制高熵邀请链接，绑定当前资源、VIEWER/EDITOR、有效期和撤销状态，默认有效 7 天且可选 1 小时到 30 天，只存 token hash。链接 MUST NOT 授予 ADMIN/OWNER，打开 SHALL 先登录 kwiki。

#### Scenario: User accepts a valid document invitation
- **WHEN** 用户登录后激活有效文档邀请
- **THEN** 通过幂等 POST 接受该页的指定角色，不增加其整个知识库成员资格；GET 预览不写成员数据

#### Scenario: Link is expired, revoked or no longer authorized
- **WHEN** 链接过期、撤销、资源归档或发行者失去分享权限
- **THEN** 接受操作不授予权限，并显示无效状态，不泄露受限资源内容

### Requirement: Resource-level approval switch
资源 SHALL 提供默认开启的审核开关。开关关闭 SHALL 在登录后接受链接时直接加入；开启 SHALL 创建待审申请，未批准前不授予权限。审核 SHALL 仅允许资源创作者/管理员、文档所属知识库管理者及超管。

#### Scenario: Approval is disabled
- **WHEN** 用户登录后通过有效邀请链接接受邀请且当前审核关闭
- **THEN** 立即获得链接指定权限，无须人工审核，重复接受不重复插入或降低已有角色

#### Scenario: Approval is enabled and reviewed
- **WHEN** 用户提交申请，授权审核者选择批准或拒绝
- **THEN** 批准前重新验证链接、资源、申请状态和审核权限；仅有效批准授予角色，拒绝不授权

#### Scenario: Approval settings or reviewer permissions change
- **WHEN** 审核开关变化或审核者在提交审批前失去角色
- **THEN** 新接受按当前开关处理，已有待审不被批量隐式批准，失权审核者的审批被拒绝

### Requirement: Creator-managed administrators and inherited document management
资源创作者 SHALL 能增删本资源 ADMIN，知识库 OWNER/ADMIN SHALL 默认管理所属文档；继承身份 SHALL 标明来源且不可在文档层移除。普通 ADMIN MUST NOT 任免其他管理员或转交创作者权利。

#### Scenario: Owner manages resource administrators
- **WHEN** 创作者增删一名直接管理员
- **THEN** 当前资源权限即时更新并审计；通过文档页面移除继承管理员被拒绝并指明知识库来源

### Requirement: Atomic transfer of creator rights
知识库/文档 SHALL 支持当前 owner 发起、有效资源协作者确认的转交；接受 SHALL 原子更新唯一 owner 与角色，原 owner 降为 EDITOR，保留 created_by/修订作者历史。转交默认 7 天有效且可撤销。

#### Scenario: Recipient accepts ownership
- **WHEN** 有效受让人确认未过期转交
- **THEN** 资源恰有一个新 owner，原 owner 为 EDITOR，权限版本更新并留下审计；知识库转交不改写每篇文档 owner

#### Scenario: Concurrent or invalid transfers
- **WHEN** 两个转交同时确认、原 owner 已变更或接收者已失去资格
- **THEN** 至多一个成功，其他返回冲突/失效；不得出现无主或双 owner 资源
