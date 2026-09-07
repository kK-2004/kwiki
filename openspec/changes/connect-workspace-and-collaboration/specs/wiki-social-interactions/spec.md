## ADDED Requirements

### Requirement: Idempotent page likes, favorites and profile lists
可读文档 SHALL 支持点赞/取消和收藏/取消，使用唯一用户关系及设定目标状态的幂等 API；标题下 SHALL 展示赞/收藏/评论数与本人状态，用户主页 SHALL 分页展示真实点赞/收藏文档并支持跳转。

#### Scenario: Like or unlike request is retried
- **WHEN** 同一用户重复提交点赞或取消点赞
- **THEN** 业务关系只变化一次，计数不重复增减，页面状态与数据库一致

#### Scenario: User opens a profile interaction list
- **WHEN** 用户访问点赞列表并点击一个条目
- **THEN** 展示真实关联且当前可读的 Wiki 并跳转，归档/失权文档不泄露标题内容

### Requirement: Root-parent and direct-reply comment structure
一级评论 parentId/replyTo SHALL 为空；所有回复 parentId SHALL 指向所属一级评论，replyTo SHALL 指向直接被回复评论。系统 SHALL 验证同页、有效根和目标，按根与回复分别分页展示。

#### Scenario: User replies to a reply
- **WHEN** B 回复一级评论 A，C 再回复 B
- **THEN** B.parentId=A、B.replyTo=A，C.parentId=A、C.replyTo=B，前端显示同根下的回复关系

#### Scenario: Reply targets a different page or hidden root
- **WHEN** 请求的回复目标跨页、被删除或所属根已删除
- **THEN** 拒绝写入，不产生孤立或不可访问的新回复

### Requirement: Comment likes and deletion authorization
可见评论 SHALL 支持幂等点赞/取消；删除 SHALL 只允许作者或资源管理者并对目标记录做逻辑删除，不能物理删除历史记录。

#### Scenario: Reader interacts with a deleted comment
- **WHEN** 用户请求点赞/回复已删除评论，或普通用户删除他人评论
- **THEN** 拒绝操作，计数和记录不变

### Requirement: Single-row synchronous deletion with hidden root threads
删除 SHALL 同步只修改目标评论一条 deletedAt。若目标是根，API 与前端 SHALL 立即隐藏其全部 parentId 子回复，包括后续分页/深链；非根删除 SHALL 仅隐藏自己，其他同根回复保留。

#### Scenario: Root with replies is deleted
- **WHEN** 作者删除有 N 条可见回复的根评论
- **THEN** 同步只逻辑删除根行，整组从页面/API 消失，可见评论统计减少 N+1，子回复不被同步批量更新

#### Scenario: Reply is deleted but others refer to it
- **WHEN** 用户删除非根回复 B 而同根的 C.replyTo=B
- **THEN** B 隐藏、C 保留并显示原回复已删除，不删除整个一级评论组

#### Scenario: Reply races root deletion
- **WHEN** 新回复和根删除同时提交
- **THEN** 按根锁定序列化，回复要么先成功并计入删除扣减，要么因根已删而失败，不能留下可见孤儿或错误计数

### Requirement: Replaceable daily cleanup strategy
系统 SHALL 使用独立评论清理策略接口与可替换调度触发器，默认每日 Asia/Shanghai 01:00 批量逻辑删除根已删的回复；集群运行 SHALL 防重、幂等、可恢复且不重复扣减统计，为未来 XXL-JOB 适配保留接口。

#### Scenario: Scheduled cleanup runs twice or resumes
- **WHEN** 01:00 任务重复触发或中途失败后按游标续跑
- **THEN** 已隐藏回复最终被批量逻辑删除，已处理记录无副作用，前台统计不再次下降

#### Scenario: Scheduler implementation is replaced
- **WHEN** 将 SpringScheduled 触发器替换为未来 XXL-JOB 适配器
- **THEN** 继续调用同一清理策略，无须修改评论领域删除规则
