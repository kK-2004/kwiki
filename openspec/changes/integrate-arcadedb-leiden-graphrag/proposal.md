## Why

KWiki 当前依靠 Elasticsearch 的 CHILD Chunk 混合召回和 PARENT 证据回答，缺少实体关系与主题社区对跨片段问题的补充。引入 ArcadeDB 知识图谱和离线 Leiden 社区发现，让社区帮助定位搜索范围，再用种子实体获取有界局部关系，所有答案继续追溯到有权限的当前原文。

## What Changes

- 从已发布页面及可索引附件的分块中异步抽取实体、关系和来源证据，写入 ArcadeDB；支持幂等重试、修订更新、归档、删除及恢复。
- 在 ES CHILD Chunk 冗余 `entityIds` 及实体映射版本/状态，随原文命中一起返回种子实体，省去在线 Chunk → MENTIONS → Entity 查询；只有后续关系扩展按需访问图数据库。
- 按知识库构建不可变图快照，仅用 Entity–Entity 语义关系的独立投影执行离线 Leiden，持久化社区成员与版本。
- 生成有来源约束的社区摘要，建立独立 ES 社区混合检索索引；社区 ID 仅在所属图版本内有效。
- 查询时并行召回 Chunk 和可安全使用的社区，用 `community + seed entity` 限定 1～2 hop 图扩展；将图补充证据纳入现有 QA、上下文预算、引用和调试轨迹。
- 通过单一已发布快照清单绑定图版本与社区物理索引，提供构建、校验、发布、回滚和故障降级；不依赖跨 MySQL、ES、ArcadeDB 的分布式原子事务。
- 首版已确认使用 ArcadeDB 内置无权 Leiden；不引入 Python worker 或加权算法实现，保留算法适配边界供后续独立变更扩展。
- ArcadeDB 由用户独立部署，本项目提供环境化连接、超时、凭据、健康检查和受控 schema 配置。
- 社区按知识库全来源构建；对来源权限或本次选择范围不完整的知识库，关闭社区及图增强，沿用现有 Chunk/QA 流程。
- 每天 Asia/Shanghai 02:00 调度全量构建；现有管理后台支持全部知识库/指定知识库手动任务，扩展 CHUNK/COMMUNITY 索引管理，每个图构建任务固定并展示两个 ES 版本。
- 初始单库上限为 50,000 实体、250,000 条语义关系，全局单库串行构建；限额可配置，超限明确失败，不静默截断。

## Capabilities

### New Capabilities

- `knowledge-graph-projection`：实体/关系抽取、来源追溯、ArcadeDB 存储、ES entityIds 冗余与内容生命周期。
- `leiden-community-snapshots`：隔离实体投影、离线社区发现、不可变快照和版本发布。
- `community-summary-retrieval`：摘要生成、独立 ES 社区索引、混合召回与摘要权限门禁。
- `graph-augmented-retrieval`：种子定位、有界图扩展、原文证据融合与现有 QA 链路集成。
- `graph-operations`：独立服务配置、02:00 调度、全量/单库任务、双 ES 版本标记、现有后台的社区索引管理、恢复与运行限额。

### Modified Capabilities

无正式主规格增量：仓库当前没有 `openspec/specs/`，既有规格仍位于历史 change 中。本变更新增上述规格，并与现有 `agentic-retrieval-strategies`、`qa-gated-retrieval-recovery`、`document-audience-authorization` 和 `search-index-version-management` 的已实现契约兼容；不改写这些历史 change。

## Impact

- 后端：`indexing/job`、版本化分块流水线、`rag/retrieval`、`LangGraphAgenticWorkflow`、权限/生命周期出站守卫与管理接口；新增图领域及 ArcadeDB 适配器。
- 数据设施：新增运维提供的 ArcadeDB 服务；MySQL 增加图构建清单/任务/发布元数据；ES 增加独立社区物理索引，并通过现有索引版本机制扩展 CHILD 的实体字段，不替换 `kwiki-chunks` 检索职责。
- AI 调用：增加异步实体关系抽取、社区摘要与摘要 embedding；复用现有模型客户端，单独限流、计费和失败重试。
- 运维：沿用 common SDK 分布式锁、现有后台认证与审计模式；增加图服务配置、能力探测和定时构建状态；在现有 `admin-frontend` 显示 CHUNK/COMMUNITY 两类索引及任务版本配对，不创建独立后台或 ArcadeDB 部署。
- 兼容：默认关闭图增强；ArcadeDB 或图构建故障不阻断内容发布与原有 Chunk 检索。权限变化仍按现有安全契约中止失权输出。
- 当前交付边界：已确认设计并生成 `tasks.md`，与 `proposal.md`、`design.md` 和 `specs/**/spec.md` 一同作为后续实施依据；本次不执行数据库迁移、连接真实服务或开始实现。
