## ADDED Requirements

### Requirement: Permission-scoped username candidate ranking
@ 查询 SHALL 限定于当前文档有读/编辑权限的活跃用户，先限制授权集合再按用户名完全匹配、前缀、包含及稳定 username/id 次序排序，支持分页和查询上限。后端 MUST NOT 返回全站无权候选。

#### Scenario: User types a username prefix
- **WHEN** 输入 @ 后追加部分用户名
- **THEN** 返回权限内最匹配的候选列表，已禁用或无文档权限的用户不出现，即使姓名更匹配

### Requirement: Keyboard and pointer multiple mention selection
评论输入 SHALL 支持 @ 弹出候选、方向键移动、Enter 确认、Escape 关闭、鼠标选择与多个用户 token，保留 userId/label，输入搜索 SHALL 防抖并取消陈旧请求。

#### Scenario: User selects several recipients while search races
- **WHEN** 用户快速输入前缀并通过键盘和鼠标添加多个 @
- **THEN** 旧搜索响应不覆盖新候选，多个选中用户稳定保留，提交使用 userId 而非可伪造显示名

### Requirement: Transactional mention notifications
评论与 mention/notification SHALL 同事务保存，发送时重新校验收件人权限，每评论/每收件人去重且默认不通知本人；客户端提供的 userId MUST NOT 绕过候选权限。

#### Scenario: Permission changes between selection and submit
- **WHEN** 选中用户在评论提交前失去读取权限
- **THEN** 返回可修正的候选失效错误，不向该用户创建含私有划词的通知

#### Scenario: Comment submit is retried
- **WHEN** 同一幂等评论操作重复提交且 @ 同一用户多次
- **THEN** 只生成一条该评论对应收件人的通知

### Requirement: Unread message center with authorized deep links
侧栏消息中心 SHALL 显示本人当前可访问未读数，点击在右侧主内容展示分页列表，支持单条/全部已读和点击跳转 Wiki 划词/评论；读取消息 SHALL 复核资源权限及删除状态。

#### Scenario: Recipient opens an unread mention
- **WHEN** 接收者点击未读 @ 消息
- **THEN** 幂等标为已读、badge 更新，并跳到对应 Wiki 高亮锚点且展开评论

#### Scenario: Recipient lost permission or comment was deleted
- **WHEN** 旧通知对应文档失权或评论已不可见
- **THEN** 列表不暴露旧标题/文本，仅给不可访问状态或过滤该项，badge 与可访问未读集合一致，不能深链绕过权限

#### Scenario: Another tab reads a notification
- **WHEN** 用户重新聚焦窗口或下一次可见窗口轮询
- **THEN** 未读数和列表从后端刷新，轮询本身不续签 JWT
