## ADDED Requirements

### Requirement: Database-first atomic cached statistics
文档赞/收藏/可见评论数及评论赞数 SHALL 以 MySQL 业务关系为准；业务成功提交后 SHALL 同步原子更新对应 Redis 数值并设 1800 秒 TTL。查询命中 SHALL 直接读缓存，业务操作后刷新 TTL，查询本身不刷新 TTL。

#### Scenario: Successful or rolled-back write
- **WHEN** 点赞/收藏/评论事务分别成功或回滚
- **THEN** 成功提交后按真实 delta 原子更新 Redis 并刷新 TTL；回滚不修改缓存

#### Scenario: Idempotent operation is repeated
- **WHEN** 已点赞用户再次设为点赞或已取消用户再次取消
- **THEN** 数据库与 Redis 数值不重复变化，成功操作可刷新 30 分钟 TTL，计数不能小于零

### Requirement: Locked complete refill on cache miss
缓存未命中 SHALL 申请资源级分布式锁、二次检查，从数据库一致快照查询完整统计和版本并原子回填 TTL；锁等待 SHALL 有界，未持锁者不能无保护回填。

#### Scenario: Multiple readers miss simultaneously
- **WHEN** 多个读者同时发现同一资源统计缓存缺失
- **THEN** 持锁者完成一次完整回填，其余重读；超时降级查 DB，不把不存在的字段当 0 返回

### Requirement: Coordinated writes and refills avoid duplicate counting
写入与回填 SHALL 使用同一资源协调锁及单调数据库版本；缓存增量 SHALL 检查版本以拒绝重复、陈旧或跳跃写。业务提交后 key 缺失 SHALL 回填包含当前提交的聚合，MUST NOT 在回填后再叠加同一操作。

#### Scenario: Cache expires while a like commits
- **WHEN** 点赞提交前后 key 过期且另一个读取请求尝试回填
- **THEN** 串行化/版本校验使最终缓存等于数据库总数，既不把总数置为 1，也不多算本次点赞

#### Scenario: Update is duplicated, delayed or lock ownership is lost
- **WHEN** 同版本重复脚本、旧版本延迟到达或锁续租失败
- **THEN** 不重复增量、不覆盖新版本；无法安全更新时走失效/修复流程而非无锁回填

### Requirement: Root deletion and cleanup share visible-count semantics
评论统计 SHALL 只包含未删除且根未删除的可见评论。根删除 SHALL 一次扣除整组当前可见数量，每日隐藏回复清理 SHALL 不再次扣减。

#### Scenario: Deleted thread is refilled before nightly cleanup
- **WHEN** 根评论已删、回复行还未逻辑删除且 Redis 缺失
- **THEN** 数据库回填查询排除整组回复，与前端展示和删除时扣减结果一致

### Requirement: Recoverable Redis failures without duplicated business writes
Redis 失败 SHALL 不回滚已成功业务，也不返回让用户误判业务失败的错误。系统 SHALL 返回真实业务状态/DB 统计，记录持久化可合并修复工作并在恢复后按锁重建；不确定增量 MUST NOT 盲目重放。

#### Scenario: Redis timeout occurs after a database commit
- **WHEN** 数据库已提交而 Redis 更新超时，执行结果不确定
- **THEN** API 仍报告业务成功，尽力失效缓存并保留修复任务，修复从数据库重建而非再执行一次增量

#### Scenario: Repair runs after a later successful operation
- **WHEN** 修复记录对应版本早于当前数据库/缓存版本
- **THEN** 修复按当前数据库状态重建或确认已修复，不覆盖为旧统计，TTL 仍为 30 分钟
