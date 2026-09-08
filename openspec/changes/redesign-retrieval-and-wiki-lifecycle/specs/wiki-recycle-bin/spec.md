## ADDED Requirements

### Requirement: Two confirmations precede archive
系统 MUST 对 Wiki 页面所有归档入口依次显示两个独立模态确认，知识库归档 SHALL 使用相同流程；第二个弹窗说明影响范围、7 天保留期和立即停止检索，仅在最终确认后提交一次幂等归档操作。

#### Scenario: User cancels either modal
- **WHEN** 用户关闭或取消任意一次确认
- **THEN** 不调用归档接口，页面、树、知识库和 ES 不改变

#### Scenario: User confirms twice or double clicks
- **WHEN** 用户完成两次确认并重复点击提交
- **THEN** 页面/树/设置入口都只执行一次逻辑归档，服务端幂等重试不重置过期时间

### Requirement: Seven day batch retention with scoped restoration
系统 SHALL 将页面有效子树或知识库有效内容逻辑归档为可恢复批次，保存操作者、精确项目、priorState、原父关系、archivedAt、purgeAfter=archivedAt+168h 和生命周期版本。归档与恢复 MUST 验证整个范围的现有管理权限。此前单独归档内容 SHALL 保留自身批次与到期时间。

#### Scenario: Page subtree is archived
- **WHEN** 有权管理整个子树的用户归档父页面
- **THEN** 该 ACTIVE 子树进入同一回收批次，原内容/修订保留，独占索引源隔离，共享附件不被误删

#### Scenario: Permission fails for part of subtree
- **WHEN** 归档者没有所需的整个操作范围管理权限
- **THEN** 操作整体拒绝，不产生部分归档

#### Scenario: Knowledge base is restored
- **WHEN** 管理者在保留期内恢复已归档知识库
- **THEN** 恢复该批次的内容、权限与关系，已独立归档项目保持原归档状态

#### Scenario: Page parent is unavailable
- **WHEN** 保留期内恢复页面时原父已不存在或仍归档，但知识库有效
- **THEN** 页面子树恢复到知识库根目录并向用户说明位置调整

#### Scenario: Knowledge base is still archived
- **WHEN** 用户尝试单独恢复其内部页面
- **THEN** 返回明确冲突并要求先恢复知识库，不留下可访问的孤立页面

### Requirement: Recycle bin is accessible only to authorized managers
系统 SHALL 提供工作区已归档知识库入口和知识库内回收站，支持按类型分页列举与恢复，显示归档者、归档时间、到期时间和索引状态。系统 MUST 根据保存的资源管理权限检查已归档对象，而非要求对象 ACTIVE。

#### Scenario: Manager lists archived resources
- **WHEN** 有管理权限的用户访问回收站
- **THEN** 能看到并在未过期时恢复其授权范围项目，普通成员看不到无管理权的归档元数据

#### Scenario: Expiry or purge races restoration
- **WHEN** now >= purgeAfter 或清理已在持锁执行
- **THEN** 恢复返回明确 409/410 原因，不恢复已过期数据、不与物理删除同时成功

### Requirement: Archive immediately excludes all search paths
系统 MUST 在归档事务提交后立即通过权威生命周期过滤阻止页面/知识库在关键词、向量、工作区搜索、父回取、引用和运行中问答输出中被使用。系统 SHALL 在当前归档请求中同步删除对应全部父/子 ES chunks 并 refresh，事务内同时持久化可靠删除任务。

#### Scenario: Archive succeeds with ES available
- **WHEN** 页面或知识库进入回收站且同步删除成功
- **THEN** 返回 SYNCED 前 ES 删除对搜索可见；页面删除不影响其他资源，知识库删除覆盖所有页面/附件/源资源及修订 chunks

#### Scenario: Elasticsearch is unavailable
- **WHEN** 数据库归档已提交但 ES 删除失败或部分失败
- **THEN** 返回 202/PENDING 和检索已停用文案，资源仍被所有检索路径排除，可靠任务重试删除而不伪报完全同步

#### Scenario: Stale cache or in-flight answer exists
- **WHEN** 请求持有归档前 scope 缓存或已检索证据
- **THEN** 生命周期版本检查和输出边界重验使归档生效；不能构造可信范围时拒绝查询而非返回过期缓存内容

### Requirement: Lifecycle fencing prevents stale index work
系统 MUST 将归档、恢复、索引 upsert/delete 按资源生命周期版本和互斥执行，旧任务不得复活已归档资源或删除已恢复索引。恢复 SHALL 重建当前有效已发布修订并显示重建状态。

#### Scenario: Delayed upsert races archive
- **WHEN** 老索引任务在归档之后执行或完成
- **THEN** 它被状态/版本检查阻止或在互斥范围内补偿删除，归档后 chunks 不重新可检索

#### Scenario: Delayed delete races restoration
- **WHEN** 归档删除任务在恢复版本之后重试
- **THEN** 旧任务不删除恢复的新版本索引，页面仅当前已发布内容重新可检索

### Requirement: Daily physical cleanup is bounded observable and idempotent
系统 SHALL 每天 Asia/Shanghai 01:00 执行清理，仅物理删除 purgeAfter < 本次固定 now 的仍归档本地数据。清理 MUST 使用多实例互斥、批处理、版本复验和 FK/逻辑关系有序删除；失败项可重试，不误删共享附件、有效对象或共享会话。内容中心远端文件不调用不存在的 SDK 删除接口。

#### Scenario: Seven day boundary
- **WHEN** 任务执行时一个项目恰好归档 168 小时，另一个超过 168 小时
- **THEN** 恰好到期项本次不物理清理且不可恢复，超过的项进入本次清理，未满 168 小时项保留

#### Scenario: Multiple instances or failed batch
- **WHEN** 两个实例同时触发任务或一个批次中某对象清理失败
- **THEN** 同一对象不会重复破坏性处理，失败不阻断其他批次且后续可重试，已恢复版本跳过

#### Scenario: Cleanup logs an empty or nonempty run
- **WHEN** 每日清理开始、处理批次和结束
- **THEN** INFO 日志记录 jobId、cutoff、扫描/删除/跳过/失败数量和耗时；零记录仍输出开始和结束

#### Scenario: Historical archives have no reliable timestamp
- **WHEN** 上线回填原有 ARCHIVED 数据
- **THEN** 为其提供从回填时刻起完整 7 天保留期，不根据 updatedAt 推断并立刻删除

#### Scenario: Schema migration is allocated
- **WHEN** 实现新增归档/引用持久结构
- **THEN** 先枚举完整 tracked/untracked Flyway 集，分配唯一后续数字版本，通过现有只读验证且不修改历史迁移
