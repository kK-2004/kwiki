# ArcadeDB 图增强运行手册（GraphRAG / Leiden 社区）

本文档面向运维与后台管理员，覆盖独立 ArcadeDB 服务接入、每日调度、后台双索引操作、限额调整、失败恢复与灰度/回滚。外部加权 Leiden worker 明确列为**后续范围**，本版本只使用 ArcadeDB 内置无权 Leiden（`ARCADEDB_NATIVE_UNWEIGHTED`）。

## 1. 默认关闭与开启条件

图功能默认关闭（`KWIKI_GRAPH_ENABLED=false`）。关闭时：

- 不建立任何 ArcadeDB 连接，不做能力探测；
- 内容发布、Chunk 检索、问答链路完全不受影响。

开启前必须由运维独立部署 ArcadeDB 服务（本项目不生成、不启动数据库），并完成：

1. 锁定服务版本（建议固定到一个已验证的发行版，写入 `KWIKI_ARCADEDB_REQUIRED_SERVER_VERSION`）；
2. 生成一个访问 token：该 token 需同时具备在线读与建库/临时库权限（创建/删除数据库，临时投影库需要）；不细分读/构建两组凭据；
3. 通过能力契约验收（见第 7 节）。

## 2. 环境变量清单

| 变量 | 默认 | 说明 |
| --- | --- | --- |
| `KWIKI_GRAPH_ENABLED` | `false` | 图增强总开关 |
| `KWIKI_GRAPH_ALGORITHM_MODE` | `ARCADEDB_NATIVE_UNWEIGHTED` | 首版固定，不得切换其他算法 |
| `KWIKI_GRAPH_SCHEDULE_CRON` | `0 0 2 * * *` | 每日全量构建触发时刻 |
| `KWIKI_GRAPH_SCHEDULE_ZONE` | `Asia/Shanghai` | 调度时区 |
| `KWIKI_GRAPH_AUTO_PUBLISH` | `false` | 默认手动发布；开启后自动发布仍复用同一门禁 |
| `KWIKI_GRAPH_READ_LEASE_DURATION` | `10m` | 请求级读取租约时长（覆盖最大请求时长） |
| `KWIKI_GRAPH_RETENTION_MIN_SNAPSHOTS` | `2` | 每配对最少保留（当前/上一版本） |
| `KWIKI_GRAPH_RETIREMENT_GRACE` | `30m` | 退役清理宽限期 |
| `KWIKI_ARCADEDB_ENDPOINT` | 空 | 独立服务地址，无 localhost 兜底 |
| `KWIKI_ARCADEDB_DATABASE` | 空 | 业务图数据库名 |
| `KWIKI_ARCADEDB_TOKEN` | 空 | 访问 token（Bearer），需同时具备在线读与建库/临时库权限 |
| `KWIKI_ARCADEDB_CONNECT_TIMEOUT` | `3s` | 连接超时 |
| `KWIKI_ARCADEDB_QUERY_TIMEOUT` | `1500ms` | 在线查询上限 |
| `KWIKI_ARCADEDB_BATCH_WRITE_TIMEOUT` | `30s` | 批写上限 |
| `KWIKI_ARCADEDB_ALGORITHM_TIMEOUT` | `15m` | Leiden 单次执行上限 |
| `KWIKI_ARCADEDB_TEMPORARY_DATABASE_PREFIX` | `kwiki_leiden_` | 临时投影库前缀 |
| `KWIKI_ARCADEDB_REQUIRED_SERVER_VERSION` | 空 | 锁定的兼容服务版本 |

容量限额（`KWIKI_GRAPH_MAX_ENTITIES` 等，见 `GraphProperties.Capacity`）：每库 50,000 实体、250,000 语义关系、1,000,000 来源记录、5,000 社区摘要；20,000 实体 / 100,000 关系起预警。超限明确失败，不会删孤点或截断来源伪装成功。调整限额需在容量演练（第 7 节）后进行并同步到后台展示。

## 3. 每日 02:00 调度与恢复

- 每天在 `schedule-zone` 的 cron 时刻触发一个全量批次；`graph_schedule_trigger` 以日期为主键，多实例只触发一次。
- 当日漏跑：应用恢复后若触发时刻已过且当天无记录，最多补跑一次（`CATCH_UP`）。
- 仍有未完成批次：当日记录 `SKIPPED_ACTIVE` 并关联该批次，先恢复它，不创建重复批次。
- 手动任务与定时批次共用提交入口；每库唯一活动 run（数据库唯一键）+ 全局构建槽位（分布式锁 `kwiki:lock:graph:build-slot`）保证互斥，冲突返回 BUSY。
- 取消/HTTP 超时不等于远端算法停止：状态未知时保留算法槽位并标记 `NEEDS_ATTENTION`，确认远端结束后才清理临时库。

## 4. 后台双索引操作（`/admin/`）

- **索引管理**（现有页面）：增加 CHUNK / COMMUNITY 类型切换。CHUNK 保留创建、构建、补齐、热切换、清理；COMMUNITY 展示版本、每库物理索引（`kwiki-communities-v{communityIndexVersion}-kb{kbId}`）、配套 Chunk/图版本与构建状态。
- **知识图谱任务**（新页面 `/admin/knowledge-graphs`）：全量/指定知识库任务表单、Chunk 目标版本选择、批次/子任务详情（CHUNK/COMMUNITY 双徽标始终可见；纯 Chunk 任务的 COMMUNITY 显示“—（不涉及）”）、发布/回滚/退役/清理、调度与服务状态、审计。
- 发布默认手动；`autoPublish` 开启时自动发布仍走同一校验/epoch/CAS 门禁，且只推进任务固定 Chunk 版本下的配对，绝不切换 Chunk 读别名。
- 所有写操作要求 `Idempotency-Key`，重复投递重放原结果；页面刷新后从持久化状态恢复进度。

## 5. 失败恢复

| 现象 | 处理 |
| --- | --- |
| 单库子任务失败 | 其他库继续；后台「重试」将 FAILED/CANCELLED/STALE 回到 QUEUED，复用原 run 与版本对 |
| 发布前 epoch 变化 | 候选转 STALE；需重新捕获来源（新批次），在线指针不变 |
| 发布后崩溃 | MySQL 指针是唯一提交点；新请求读新配对，候选可恢复或清理，无混版窗口 |
| 算法远端状态未知 | `NEEDS_ATTENTION` 保留槽位与临时库；确认结束后清理，可幂等重试 |
| 清理部分失败 | 快照保持 DELETING，剩余资源记录在 `graph_resource_reference`，重试只删剩余项 |
| 图/ArcadeDB 故障 | 在线自动降级为原有 Chunk 检索；权限撤销按出站守卫中止输出，不当作普通故障降级 |

回滚：后台对配对 `(kbId, chunkIndexVersion)` 切回完整且 Chunk 流水线兼容的历史快照；快照 epoch 过期时社区摘要保持禁用，仅逐条验证仍有效的图事实。

## 6. 灰度与回滚

1. 按知识库灰度：先为试点库构建/发布快照，其余库保持无图运行；
2. 关闭增强只需 `KWIKI_GRAPH_ENABLED=false` 或不发布快照；关闭后 Chunk/QA 路径继续工作；
3. 应用回滚不删除新增存储；退役快照按宽限期+读取租约清理（至少保留当前/上一版本）。

## 7. 专用环境契约验收与容量演练（上线前必做）

默认测试不依赖真实 ArcadeDB/Docker。专用环境验收通过环境变量显式启用，未配置时测试**明确跳过**（结果不构成已验收结论）：

```bash
KWIKI_GRAPH_CONTRACT_ARCADEDB=https://arcadedb.internal \
KWIKI_GRAPH_CONTRACT_ARCADEDB_TOKEN=... \
./mvnw test -Dtest=GraphExternalEnvironmentContractTest
```

覆盖：能力探测（版本/Leiden/schema/建库权限）、schema 可重入初始化、临时库内空图 Leiden 合法性与清理幂等、实体唯一索引 EXPLAIN 访问路径。

端到端与跨存储演练骨架（`GraphEndToEndDrillContractTest`）同样按环境变量显式启用，未配置时明确 skipped：

```bash
KWIKI_GRAPH_CONTRACT_MYSQL=jdbc:mysql://localhost:3306/kwiki_contract \  # 必须是专用空库
KWIKI_GRAPH_CONTRACT_MYSQL_USER=... KWIKI_GRAPH_CONTRACT_MYSQL_PASS=... \
KWIKI_GRAPH_CONTRACT_ES=http://localhost:9200 \
./mvnw test -Dtest=GraphEndToEndDrillContractTest
```

覆盖：COMMUNITY 物理索引建库→写入→BM25 读回→清理（14.4 ES 骨干）；专用库上封存→CAS 发布→读取租约→幂等重放→退役保护的生命周期（14.5 MySQL 骨干）。

容量演练（人工记录，形成上线限额依据）：在专用环境构建接近初始上限的库（实体≈50,000、关系≈250,000、社区≈5,000），记录 ArcadeDB 内存/磁盘、各阶段耗时、模型成本，并验证 1～2 hop 遍历在底层真实受 `maxEdgesPerNode/maxEdgesTotal` 约束（EXPLAIN 与访问计数）。

### 端到端演练清单（需真实环境执行）

1. 页面/附件发布 → 抽取任务 → ES entityIds 回填 → 离线 Leiden → 社区摘要 → 校验（`graph_snapshot.validation_json` 全绿）→ 手动发布 → 原文引用问答；
2. 无全来源权限用户走原有 Chunk 流程（社区搜索/图调用次数为零）；
3. 全量与单库任务各一次，核对 CHUNK/COMMUNITY 双版本徽标不漂移；
4. 构建中切换 Chunk 别名：任务继续使用原版本对；
5. 进程崩溃恢复：发布前后各一次，确认无混版；
6. 权限/生命周期变化、回滚、退役清理与图服务中断演练：旧请求不混版，图故障不阻断有效 Chunk 回答。

## 8. 影子评估记录

上线前在影子环境评估以下已知风险类别，评估结论记入发布检查单：

- **Spring 事务自调用**：图构建服务的发布事务（`GraphSnapshotPublicationService`）经代理调用；若新增内部自调用路径绕过 `@Transactional`，发布将失去 epoch 行锁保护——评审时专门检查自调用点。
- **Redisson watchdog 续期**：构建槽位使用 watchdog 变体（自动续期）。若 worker 进程假死但锁仍被续期，需依赖数据库租约/fencing（`fencing_token`）使旧 worker 无法推进权威状态；演练中验证 kill -9 与长 GC 场景下的接管时间。
- **普通问题**：ArcadeDB HTTP 4xx/5xx 已分类为脱敏错误；确认监控告警只基于分类码，不携带 token 或正文。

## 9. 后续范围（本期不实现）

- 外部加权 Leiden worker（Python igraph/leidenalg）与 `weightPolicyVersion` 权重规则；
- 跨知识库实体合并、层级/重叠社区、按用户拆分的社区图；
- 基于全部社区报告的 global map-reduce 问答。
