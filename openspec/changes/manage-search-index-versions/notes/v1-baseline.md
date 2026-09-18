# 当前部署 v1 基线（任务 1.1 记录）

本文件记录实施本变更前已部署的 v1 物理索引配置事实，作为 `search_index_version` 首条记录、
v1 收养（adoption）判定与 mappingHash 比对的输入。来源为 2026-09-11 的主干代码与工作区。

## v1 结构配置

| 项 | 值 | 代码来源 |
| --- | --- | --- |
| 逻辑版本号 | 1（`kwiki.indexing.bootstrap.version` 默认值） | `ElasticsearchIndexBootstrap` |
| 物理索引名 | `kwiki-chunks-v1`（`kwiki-chunks-v{n}`） | `ElasticsearchIndexManager.indexNameFor` |
| 读写别名 | `kwiki-chunks` | `ElasticsearchIndexManager.ALIAS` |
| parserVersion | `kwiki-parse-1` | `IndexingWorker.PARSER_VERSION` |
| chunkerVersion | `kwiki-chunk-1` | `IndexingWorker.CHUNKER_VERSION` |
| embeddingProvider | qwen-compatible（`kwiki.qwen-embedding.base-url`，默认 DashScope compatible-mode） | `ExternalServicesProperties.QwenEmbedding` |
| embeddingModel | `${KWIKI_QWEN_EMBEDDING_MODEL:text-embedding-v4}`（本变更前固定为 `text-embedding-v4`，工作区待提交修改将其改为可配置，local profile 默认 `qwen3.7-text-embedding`） | 同上 |
| dimensions | `${KWIKI_QWEN_EMBEDDING_DIMENSIONS:1024}`，校验范围 64–2048 | 同上 |
| mappingSchemaVersion | 当前不存在独立版本号；mapping 由代码定义（见下） | `ChunkMappingBuilder` / `MappingValidator` |
| indexVersion 字段 | 写入文档携带 `indexVersion=1` | `IndexingWorker.buildVersion` |

## v1 mapping 定义与参考哈希

`ChunkMappingBuilder.buildMapping(dims)` 生成 `dynamic: strict` + 19 个固定字段：
`chunkLevel`、`chunkKey`、`parentChunkKey`、`resourceType`（keyword）；`resourceId`、`revisionId`
（long）；`kbId`、`headingPath`、`parserVersion`、`chunkerVersion`、`embeddingModel`（keyword）；
`parentOrdinal`、`childOrdinal`、`charStart`、`charEnd`、`indexVersion`、`lifecycleVersion`（integer）；`content`
（text）；`vector`（dense_vector，dims=配置维度，index=true）。`MappingValidator` 按必需字段存在性
+ 维度 + kNN 可索引做结构校验，目前**没有持久化 mappingHash**。

本变更为物理索引引入 mappingHash 时，采用 key 排序的规范化 JSON 的 SHA-256。按该规则、dims=1024
的 v1 参考哈希（供 v1 收养比对与 `search_index_version` 初始记录核对）：

```
6a6fc500a4a5ad1b425924e059d8c9b9725c2bc24879c721a748c7dffacaaf5d
```

注意：这是对映射定义的哈希，不是对 ES 返回 mapping 的逐字节哈希；ES 返回体含分析器默认值等
运行时细节，比对必须基于规范化后的 `properties`/`dynamic`/`vector.dims` 子集（任务 3.2/3.3）。

## 与工作区待提交 v1 引导修改的和解

工作区存在一组未提交修改（本变更的起点，**保留并在其上继续**，不得覆盖或回退）：

1. 新增 `ElasticsearchIndexBootstrap`（ApplicationRunner，最高优先级）：别名不存在时幂等创建
   `kwiki-chunks-v{version}`、校验 mapping、`createInitialAlias` 仅添加不移除；别名已存在则跳过。
   与本变更一致，任务 3.5 将其升级为"持久化 v1 元数据（configRevision==builtConfigRevision、
   selected、writeEnabled）"，任务 3.6 增加已有别名目标的安全收养与 NEEDS_ATTENTION。
2. `IndexingWorkerScheduler` 在消费任务前等待 `bootstrap.isReady()`：保留，后续演进为
   "存在可用物理写目标"这一更一般的启动屏障。
3. `ElasticsearchIndexManager` 维度注入从 `kwiki.indexing.embedding-dimensions` 改为
   `kwiki.qwen-embedding.dimensions`，并新增 `available()`/`aliasExists()`/`createInitialAlias()`：
   保留。任务 3.2 将把维度来源改为各版本成功构建快照而非进程级常量。
4. `kwiki.indexing.bootstrap.version`（`KWIKI_INDEX_VERSION`）：本变更后其语义收窄为
   "首次部署（无别名、无版本元数据）时创建的初始版本号"，应用发版绝不因该配置把别名切向
   更高版本（设计决策 9 / 规格 "Startup bootstraps or adopts v1"）。
5. embedding `model` 改为可配置（`@NotBlank` 重复注解清理为无关清理，保留）：这是 v1 收养
   比对必须以部署实际模型/维度为准的原因，也是任务 1.3 版本清单属性的雏形。
6. `StandardTestProperties` 已显式关闭 `kk.common.redis.enabled`/`kk.common.redisson.enabled`
   及 worker/bootstrap：与任务 1.6 一致，保留。
7. 其余（`IndexingWorker`/`ChunkIndexRepository`/`WikiImportJobService` 日志、
   `WikiMockBeans` 增加 draft mock）为无关改进，原样保留。

## v1 部署状态备注

- 生产/本地 ES 中若已存在 `kwiki-chunks` → `kwiki-chunks-v1`，数据库尚无任何版本元数据
  （相关表由任务 2.x 新增）。首次带本变更启动将走"收养"路径：读取别名目标与 mapping，
  与上表配置匹配则记为 selected/built；不匹配则 NEEDS_ATTENTION，读路径不受影响。
- 收养比对必须使用部署环境实际生效的 `KWIKI_QWEN_EMBEDDING_MODEL`/`DIMENSIONS`
  （local profile 默认 `qwen3.7-text-embedding`/1024，主干默认 `text-embedding-v4`/1024），
  而非代码默认值。
