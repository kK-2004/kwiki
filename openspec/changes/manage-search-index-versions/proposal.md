## Why

当前检索读写依赖稳定别名 `kwiki-chunks`，但缺少可持久化、可观察、可恢复的索引升级流程。内容发布与 parser、chunker、embedding、向量维度或 mapping 变更具有完全不同的成本和风险，需要在不中断查询、不中断内容更新且可快速回滚的前提下，由管理员安全地创建、重建、校验和切换物理索引。

## What Changes

- 区分两类升级：普通 Wiki 内容发布继续更新当前启用的写入索引；parser、chunker、embedding 模型、向量维度或 mapping 的程序能力随应用正常发版，但发版本身不创建索引、不重建、不切换。
- 管理员在后台保存结构配置时由服务端自动分配下一个逻辑版本号（例如 v2）；非在线版本支持继续编辑，配置修订高于最近成功构建修订时显示“待重建”，基线成功且尚未发布时显示“已重建”。
- 管理端支持对符合条件的任一版本手动点击“重建”。首次全量重建与后续手动重建共用同一个版本级分布式锁和协调入口，保证同一版本任一时刻最多只有一个重建运行。
- 引入持久化的索引版本配置修订、构建状态、扫描范围、补齐游标、校验结果、写入目标和切换审计，支持应用重启及多实例并发。
- 提供 `保存/编辑配置 → 标记待重建 → 有界全量重建 → 发起切换准备 → 开启所有启用版本双写并行补齐 → 校验 → 原子切换 → 稳定观察 → 停用/清理` 的索引生命周期。
- 统一管理端展示状态：新建或修改配置后为“待重建”，执行期间为“重建中”，基线完成后仅显示“已重建”；只有发起切换且确有差量时显示“补齐中”，补齐、校验及原子别名切换成功后显示“已发布”。
- 全量重建固定本次 MySQL 资源 ID 上界，只处理该范围；发起切换准备时，对所有未被管理员停用或删除的版本启用写入，同时从已记录的范围末尾周期补齐新增资源，并按索引事件 ID 重放范围内资源的更新、删除、归档和恢复，追平后才允许切换读别名。
- 检索始终只通过 `kwiki-chunks` 别名读取；别名切换或回滚使用同一个 Elasticsearch `_aliases` 原子操作。
- 默认保留最近两个物理索引。旧索引只由管理员显式清理；活动索引、正在重建的索引和唯一可回滚索引禁止删除。
- 在现有 Spring Boot 后端新增仅管理员可访问的索引管理 API、任务执行与审计能力。
- 新增基于 vue-pure-admin 的管理端，并由同一个后端提供认证、API 和生产静态资源服务；管理端展示索引状态、手动重建、重建/补齐进度、各版本双写统计、校验报告、切换、回滚和清理操作。
- 将 `application.yml` 中 `kk.common.redis.enabled` 与 `kk.common.redisson.enabled` 的默认值改为 `true`，把 Redis 与 Redisson 作为正式运行依赖，并同步环境变量示例和健康检查。
- 整个项目的 Redis 数据访问统一使用 kk-common `RedisUtil`，分布式锁统一使用 kk-common `DistributedLockFactory`；移除业务代码中的 `StringRedisTemplate`、手写 Redis 锁和 MySQL `GET_LOCK`/`RELEASE_LOCK` 旁路。
- 同步改造测试配置：默认离线测试显式关闭 Redis/Redisson，需要验证 SDK 的测试注入 mock/fake SDK bean，并增加架构规则防止重新引入旁路实现。

## Capabilities

### New Capabilities

- `search-index-version-management`: 定义版本化物理索引、配置快照、全量重建、增量追平、双写、校验、原子切换、回滚、保留和清理的服务端行为。
- `search-index-admin-console`: 定义管理员专用 API 与 vue-pure-admin 管理界面的访问控制、状态展示、危险操作确认和审计体验。
- `shared-redis-lock-infrastructure`: 定义 common SDK Redis/Redisson 的默认启用、统一访问边界、失败语义、分布式锁规范和测试隔离要求。

### Modified Capabilities

- 无。本仓库当前没有已归档到 `openspec/specs/` 的基础能力规格；与进行中的检索/回收站变更通过设计依赖和实施顺序协调。

## Impact

- 后端：`indexing` job/pipeline/search 模块、Elasticsearch 索引管理器、应用启动引导、管理员控制器、安全配置、调度与可观测性。
- 基础设施：`ScopeCache`、`WikiStatisticsCache`、回收站/评论清理调度器、资源索引互斥和新增的索引切换互斥统一迁移到 kk-common SDK。
- 数据库：新增索引版本、重建运行、目标写入进度、校验和操作审计等持久化结构，并通过项目现有 Flyway 机制迁移。
- Elasticsearch：读别名保持 `kwiki-chunks`；写入从别名改为显式物理索引目标；物理索引携带其成功构建时的配置修订和构建元数据。
- 前端：新增独立的 vue-pure-admin 管理端工程或构建入口，生产产物由现有 Spring Boot 应用在 `/admin` 下提供。
- 外部服务：Redis/Redisson 默认启用并成为部署依赖；重建和双写可能同时调用新旧 embedding 模型，增加吞吐、配额、存储和失败恢复要求。
- 运维：需要为管理 API、长任务、ES 别名权限、索引容量、模型凭据和审计留存建立配置与监控。
