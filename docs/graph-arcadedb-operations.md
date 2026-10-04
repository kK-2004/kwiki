# ArcadeDB 图增强运行手册（GraphRAG / Leiden 社区）

本文档面向运维与后台管理员，覆盖独立 ArcadeDB 服务接入、每日调度、后台双索引操作、限额调整、失败恢复与灰度/回滚。外部加权 Leiden worker 明确列为**后续范围**，本版本只使用 ArcadeDB 内置无权 Leiden（`ARCADEDB_NATIVE_UNWEIGHTED`）。

## 1. 默认关闭与开启条件

图功能默认关闭（`KWIKI_GRAPH_ENABLED=false`）。关闭时：

- 不建立任何 ArcadeDB 连接，不做能力探测；
- 内容发布、Chunk 检索、问答链路完全不受影响。

开启前必须由运维独立部署 ArcadeDB 服务（本项目不生成、不启动数据库），并完成：

1. 服务版本必须 ≥ **26.3.1**（从该版本起内置 Cypher 过程 `algo.leiden`；能力探测读 `GET /api/v1/server?mode=basic` 的 `version` 判断，低于此版本插件显示“不支持原生 Leiden”）。锁定服务版本（可选，生产环境建议固定到一个已验证的发行版，写入 `KWIKI_ARCADEDB_REQUIRED_SERVER_VERSION`；不填写时跳过版本兼容性检查，启动时打印告警）；
2. 用 root 在 Studio 中建好两个库：业务图库（`KWIKI_ARCADEDB_DATABASE`，如 `kwiki`）与社区检测计算库（`KWIKI_ARCADEDB_SCRATCH_DATABASE`，默认 `kwiki_leiden`）。建库/删库是服务器命令，**只有 root 能执行**，API token 无论如何授权都不行，所以应用不会自动建库；
   宿主机挂载目录需属于容器内 arcadedb 用户（`docker exec arcadedb id` 查 uid 后 `chown`），否则建库报 `Cannot create directory`；
3. 在 Studio 中创建 `at-` 前缀的 API token（长期有效；`POST /api/v1/login` 得到的会话 token 闲置 30 分钟即过期，不适合配置），授予两个库的读写与改 schema 权限；后续退役清理需要删除记录时还需删除权限。应用启动后在业务库中幂等初始化类型、属性与索引；启动时 ArcadeDB 不可达只告警，首次写图时重试；
4. 通过能力契约验收（见第 7 节）。

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
| `KWIKI_ARCADEDB_TOKEN` | 空 | `at-` 前缀 API token（Bearer），需对业务库与计算库都有读写与改 schema 权限 |
| `KWIKI_ARCADEDB_CONNECT_TIMEOUT` | `3s` | 连接超时 |
| `KWIKI_ARCADEDB_QUERY_TIMEOUT` | `1500ms` | 在线查询上限 |
| `KWIKI_ARCADEDB_BATCH_WRITE_TIMEOUT` | `30s` | 批写上限 |
| `KWIKI_ARCADEDB_ALGORITHM_TIMEOUT` | `15m` | Leiden 单次执行上限 |
| `KWIKI_ARCADEDB_SCRATCH_DATABASE` | `kwiki_leiden` | 社区检测计算库（管理员预建），每个 run 在其中建 `LeidenEntity_{runId}`/`LeidenEdge_{runId}` 类型，跑完删除 |
| `KWIKI_ARCADEDB_REQUIRED_SERVER_VERSION` | 空 | 锁定的兼容服务版本（前缀匹配，如 `24.6` 匹配 `24.6.x`）；为空时不检查版本，仅打印告警 |

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
| 执行实例崩溃 | 租约过期后其他实例接管（隔离令牌 +1），从当前阶段继续；抽取命中缓存，不重复调用模型 |
| 模型超时 / 429 / 5xx | 阶段内指数退避重试（`kwiki.graph.build.max-attempts`），耗尽或永久错误置 FAILED，后台可重试 |
| 来源 epoch 变化 | 执行器按阶段核对 `graph_source_epoch`，不一致置 STALE，重新提交新批次 |
| 管理端取消 | 运行中的子任务在块间/阶段间检测到 CANCELLED 即退出，不再写任何权威状态 |

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

业务库用 `KWIKI_GRAPH_CONTRACT_ARCADEDB_DATABASE` 指定（默认 `kwiki_contract`），计算库用 `KWIKI_GRAPH_CONTRACT_ARCADEDB_SCRATCH`（默认 `kwiki_leiden`），两者都需预建。测试会在业务库写入 kbId=9001 的数据，跑完需用有删除权限的账号执行 `DELETE FROM Entity WHERE kbId = 9001` 与 `DELETE FROM RelationEvidence WHERE kbId = 9001` 清理。

覆盖：能力探测（版本 ≥ 26.3.1、两个库存在且可访问）、schema 可重入初始化、计算库内对两个互不相连的三角形执行 `algo.leiden` 并校验分成两个社区、跑完本 run 类型已删除、图写入 UPSERT 可重入与社区回写、实体唯一索引 EXPLAIN 访问路径。

社区检测流程：在计算库中建本 run 的 `LeidenEntity_{runId}`/`LeidenEdge_{runId}`（同名残留先删）与 `entityId` 唯一索引 → Cypher `UNWIND` 分批灌入本次投影的实体与无向边 → `CALL algo.leiden('LeidenEdge_{runId}', maxIterations, resolution) YIELD nodeId, community` → 只保留 RID 属于本 run 顶点类型的结果（实测该过程返回库内所有顶点，含并行 run 的顶点）并映射回 `entityId` → `DROP TYPE … UNSAFE` 删除本 run 类型；结果回写到业务库 `Entity.communityId`。

已知引擎行为（26.9.1 实测）：两个三角形之间仅有一条桥边时，resolution=1.0 会合并为一个社区，提高 `kwiki.graph.clustering.resolution`（如 1.5）才会拆开；小图上社区粒度偏粗属引擎特性。

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

## 10. 图构建执行器与环节插件

提交的图构建批次由内置执行器真正执行：在分布式锁（`kwiki:lock:graph-build-executor`）保护下按「抽取 → 投影 → 聚类 → 摘要 → 写索引 → 校验封存」推进子任务，每阶段开始前写断点（携带租约与隔离令牌）。

### 10.1 执行器配置（`kwiki.graph.build.*`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `initial-delay` | `30s` | 应用启动后首次领取前的等待时间（`@Scheduled` initialDelay） |
| `poll-interval` | `10s` | 领取轮询间隔（`@Scheduled` fixedDelay） |
| `max-concurrent-runs` | `2` | 全局并发上限；多实例共享，领取前统计全库「活动且租约未过期」的子任务数 |
| `lease-duration` | `2m` | 子任务租约；执行线程每 1/3 周期续租，续租失败立即退出不再写状态 |
| `max-attempts` | `5` | 可重试错误的阶段内重试上限（指数退避 `retry-base-delay`×2^n，上限 30s） |
| `retry-base-delay` | `1s` | 重试基础退避 |
| `max-chunks-per-run` | `20000` | 单个子任务的来源块上限，超出直接永久失败（`CAPACITY_EXCEEDED_SOURCES`） |
| `max-summaries-per-run` | `5000` | 单个子任务的社区摘要上限 |

### 10.2 模型配置与继承规则（`kwiki.graph.models.*`）

抽取与摘要模型独立于在线问答模型，客户端在应用内构造，**不注册**为 `ChatModel`/`StreamingChatModel` Bean。

- `kwiki.graph.models.extraction.*` 与 `kwiki.graph.models.summary.*` 各支持：`base-url`、`api-key`、`model`、`request-timeout`、`max-concurrency`、`qps`、`max-retries`。
- **继承规则**：连接项（base-url / api-key / model / request-timeout）未配置时沿用问答模型 `KWIKI_ANSWER_LLM_*` 的对应值；因此只配问答模型即可开跑，单独配置任一项则覆盖该项。
- **缺省并发/频率**：抽取 4 并发 / 5 QPS，摘要 2 并发 / 2 QPS，重试 2。限流器（并发信号量 + 令牌桶）按模型全局单例，所有子任务共享。

### 10.3 插件与默认值（`kwiki.graph.defaults.*`）

三个环节可替换，插件 id 记入批次、子任务、快照，便于追溯产出来源：

| 环节 | 首个实现 | id |
| --- | --- | --- |
| 实体关系抽取 | 大模型抽取 v1 | `kwiki-extractor-1` |
| 社区检测 | ArcadeDB Leiden v1 | `arcadedb-leiden-1` |
| 社区摘要 | 大模型摘要 v1 | `kwiki-summarizer-1` |

- 默认值：`kwiki.graph.defaults.extractor` / `community-detector` / `summarizer`，启动时校验已注册，未注册拒绝启动。
- `GET /api/v1/admin/knowledge-graphs/plugins`（ROLE_ADMIN）返回三类插件 `{id,label,available,unavailableReason}` 与默认值；提交批次请求体可用可选字段 `extractor` / `communityDetector` / `summarizer` 覆盖，未注册或不可用返回 400 并附中文原因。
- 新插件实现 `GraphExtractorPlugin` / `CommunityDetectorPlugin` / `CommunitySummarizerPlugin`（复用 `IndexPlugin` 契约），注册为 Spring Bean 即被收录。

### 10.4 调用量上限与故障恢复

- 结构上限沿用 `kwiki.graph.capacity.*`（投影写入前用容量守卫校验，超限永久失败且不裁剪数据）；来源块与社区摘要另有 `kwiki.graph.build.max-chunks-per-run` / `max-summaries-per-run` 上限。
- 抽取结果按（来源块、抽取器 id、提示词版本、实体链接版本）唯一身份持久化在 `graph_extraction_result`：命中 READY 缓存直接复用，重做抽取阶段、崩溃接管、后台重试均不重复调用模型。
- 崩溃恢复：租约过期后其他实例接管（`fencing_token` +1，旧持有者全部失效）；恢复点在 SUMMARIZING 及之前一律从 EXTRACTING 重做（全部命中缓存），INDEXING / VALIDATING 复用已创建的 BUILDING 快照与来源清单，不重复创建。
- 全部子任务进入终态后批次自动收尾：全 READY/PUBLISHED → READY；部分失败 → PARTIAL_FAILED；全失败 → FAILED。
