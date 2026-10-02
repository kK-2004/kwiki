# 设计：索引字段格式插件化（子项目 1/3）

日期：2026-10-02
范围：公共插件框架 + CHUNK 索引字段格式（原「结构版本 mappingSchemaVersion」）插件化，删除结构 1、2。

## 0. 背景与总体拆分

目标：以后修改索引字段不再改散落在各处的源码分支，而是新建一个实现类；后台像对待解析器、分块器那样，通过新建版本 → 开启写入 → 存量迁移 → 校验 → 切换（以及灰度）来上线。

现状：

- 结构版本是整数 1/2/3，25 个文件中以 `>= 2`、`>= 3`、`< 3` 等硬编码分支判断字段（映射构建、文档填充、必需字段校验、图构建门槛、建索引重载等），同一个"3"有三个常量名。
- 解析器、分块器同样不是插件：解析器为 `IndexingWorker` 中的字符串常量，各处 `if (PARSER_VERSION_MULTIMODAL.equals(...))` 分支；分块器只有 `kwiki-chunk-1`。
- 灰度、迁移、双写挂在「索引版本」上：版本的配置元组（解析器、分块器、向量模型、维度、结构版本）任一项变化即新建版本，已覆盖结构版本。

总体拆分为三个子项目，依次各走设计 → 计划 → 实现：

1. **本文**：公共插件框架 + 索引字段格式插件化，删除结构 1、2。
2. 解析器插件化：`kwiki-parse-1` / `kwiki-parse-2` 的分支收进实现类，`ParserCatalog` 改由注册表驱动。
3. 分块器插件化：套用同一框架。

已确认的决策：

- 插件实现方式：Spring Bean（实现接口 + `@Component`），新增插件 = 新建一个类并重新部署。
- 标识：字符串 id，与解析器一致。现有结构 3 重新命名为首个插件 `kwiki-schema-1`。
- 灰度：解析器、分块器、字段格式三项都可选，默认取线上版本配置；向量模型与维度强制与线上一致。
- 现有环境可清空重建：迁移脚本只需保证全新部署正确，同时在非空库上不失败。

## 1. 插件模型

### 1.1 公共框架（新包 `com.kwiki.indexing.plugin`）

- `IndexPlugin`：
  - `String id()`：全局唯一，格式 `^[a-z0-9][a-z0-9-]{1,62}$`。
  - `String label()`：管理端显示名。
  - `PluginAvailability availability()`：`available()` + 不可用原因（不含任何密钥值），如「缺少 KWIKI_VISION_API_KEY」。
- `PluginRegistry<T extends IndexPlugin>`：
  - 构造时收集同类全部 Bean；id 重复或格式非法 → 启动失败，错误信息指明冲突的 id 与类名。
  - `Optional<T> find(String id)`、`T require(String id)`（不存在时抛出带 id 的异常）、`List<T> all()`（按 id 排序）。
- `IndexCapability` 枚举：
  - `RESOURCE_IDS`：文档携带图片 `contentIds`。
  - `ENTITY_LINKING`：子块携带实体关联字段。
- 能力约定：字段格式声明**提供**的能力；解析器声明**需要**的能力（子项目 2 接入，本次只在兼容判断中预留入口，解析器需要的能力暂由适配层给出：parse-2 需要 `RESOURCE_IDS`，parse-1 无）；图构建要求 CHUNK 版本的字段格式提供 `ENTITY_LINKING`。

### 1.2 字段格式插件 `ChunkIndexSchema extends IndexPlugin`

- `Map<String, Object> mapping(int embeddingDimensions)`：完整 ES 映射（含 `dynamic: strict`）。映射摘要由框架统一计算（key 排序 JSON 的 SHA-256，沿用现有规范化方式），插件不负责。
- `Set<String> requiredFields()`：校验物理索引时检查的必需字段，替代 `MappingValidator` 的分档常量。
- `void decorateParent(Map<String, Object> document, ParentChunk chunk, IndexedVersion version)`：在公共基础字段之外填本格式特有的父块字段。
- `void decorateChild(Map<String, Object> document, ChildChunk chunk, IndexedVersion version)`：同上，子块。
- `Set<IndexCapability> capabilities()`。

公共基础字段（chunkKey、parentChunkKey、kbId、resourceType/Id、revisionId、lifecycleVersion、chunkLevel、headingPath、字符位置、content、解析器/分块器/向量模型标识、indexVersion、子块 vector 等）仍由 `ChunkDocument` 统一写入，插件只追加。

父子两个方法分开：父块（较大段落，检索命中子块后用于补全上下文，无向量）与子块（参与 BM25 + 向量召回，图构建回写实体）字段不同，签名上显式区分可避免实现类漏处理。

### 1.3 首个实现 `KwikiSchemaV1`（id `kwiki-schema-1`）

字段与现结构 3 完全一致：

- 父块：基础字段 + `contentIds`（段落含图片标记时）。
- 子块：基础字段 + `vector` + `contentIds`（含图片标记时）+ `sourceChunkId`、`entityIds`（空列表）、`entityLinkingVersion`（流水线给定，缺省 `entity-linking-v1`）、`entityLinkingStatus=PENDING`。
- 能力：`RESOURCE_IDS`、`ENTITY_LINKING`。
- 其映射摘要必须与原 `ChunkMappingBuilder.mappingHash(dims, 3)` 相同（以固定参照值断言），保证改造不改变实际字段。

删除：结构 1、2 及全部按数字分档的代码；`ChunkMappingBuilder` 的结构常量（`MAPPING_SCHEMA_MULTIMODAL`、`MAPPING_SCHEMA_ENTITY_LINKING`）、`GraphBuildTargets.MIN_MAPPING_SCHEMA_VERSION`、`VersionedIndexingPipelineRegistry.LATEST_MAPPING_SCHEMA_VERSION`；`ElasticsearchIndexManager` / `ChunkMappingBuilder` 中不带字段格式参数、默认用结构 2 的重载。

## 2. 标识从整数换成字符串

### 2.1 数据库（新增 `V37__index_schema_plugin_ids.sql`，不改历史迁移）

- `search_index_version`：新增 `index_schema VARCHAR(64) NOT NULL`；删除 `mapping_schema_version`。
- `graph_build_run`、`graph_snapshot`：`mapping_schema_version` 换为 `chunk_index_schema VARCHAR(64) NOT NULL`（这两张表记录所用 CHUNK 版本的字段格式）。
- `community_index_version.mapping_schema_version`：COMMUNITY 索引自身结构，不动。
- 回填（保证非空库可执行）：3 → `kwiki-schema-1`；1 → `legacy-schema-1`；2 → `legacy-schema-2`；其他值 → `legacy-schema-<n>`。无插件认领的 id 在运行时视为不受支持。
- 步骤：先加可空列 → 回填 → 改为 NOT NULL → 删旧列。
- `mapping_hash` 保留；物理索引名 `kwiki-chunks-vN` 不变。

### 2.2 Java 模型

`int/Integer mappingSchemaVersion` → `String indexSchema`，涉及：

- `EditableIndexConfig`（`@NotBlank`）、`SearchIndexVersion`、`BuildManifestSnapshot`、`IndexedVersion`、`ResolvedPipeline`。
- 图构建：`GraphBuildBatchRequest`、`GraphBuildRunCommand`、`GraphBuildRunRecord`、`GraphBuildRepository` / `JdbcGraphBuildRepository`、`GraphBuildTargets`、`GraphBuildBatchService`、`GraphSnapshotValidationService.ConfigIdentity`。
- 按数字分支处一律改为「`ChunkIndexSchemaRegistry.require(id)` → 读插件 / 判断能力」：
  - `ChunkMappingBuilder` → 委托插件的 `mapping()`，保留统一的摘要计算。
  - `ChunkDocument` → 基础字段后调用 `decorateParent` / `decorateChild`。
  - `MappingValidator` → 基础校验（维度、vector 类型、strict）+ 插件 `requiredFields()`。
  - `ElasticsearchIndexManager.createVersionedIndex/validateIndex/recreateOfflineVersion` → 参数改为字段格式 id。
  - `IndexVersionWriteService`、`SearchIndexValidationService`、`ElasticsearchIndexBootstrap`、`IndexingWorker` 中的分支同步改造。
  - 图构建门槛：`schema.capabilities().contains(ENTITY_LINKING)`。

### 2.3 配置

- `kwiki.indexing.manifests[].mapping-schema-version` → `index-schema`（字符串）。
- 新增 `kwiki.indexing.defaults.index-schema`，默认 `kwiki-schema-1`：首次部署、新建版本、灰度的默认字段格式。启动时校验其指向已注册插件，否则启动失败。
- 默认组合（未配显式清单时）：自动生成「可用解析器 × 分块器 × 可用字段格式」中能力兼容的全部组合，向量档案为 `default`（沿用 `kwiki.qwen-embedding.*`）。新增插件后自动成为可选项。
- 显式清单保留，作为白名单限制可选组合；清单引用未注册的字段格式 → 启动失败，错误信息指明清单项 id。
- 删除上一轮的「取最高结构版本」逻辑（`ParserCatalog.latestConfigFor` 等），由 `defaults.index-schema` 取代。

### 2.4 接口

- 管理端版本配置 JSON：`configuration.mappingSchemaVersion` → `configuration.indexSchema`。
- 图构建提交接口删除已不采信的兼容字段 `mappingSchemaVersion`；图构建相关响应中的结构版本字段改为 `chunkIndexSchema`。

## 3. 管理端、首次部署、异常处理

### 3.1 管理端

- 新接口 `GET /api/v1/admin/search-indexes/plugins`（ROLE_ADMIN）：返回 `schemas`、`parsers`、`chunkers` 三个列表，每项 `{id, label, available, unavailableReason, capabilities | requiredCapabilities}`。本次解析器、分块器由 `ParserCatalog` 与常量适配为同一格式；子项目 2、3 完成后改由各自注册表提供，接口格式不变。
- 新建 / 编辑版本表单：解析器、分块器、字段格式改为下拉框；新建默认取线上版本配置，字段格式默认 `defaults.index-schema`；去掉结构版本数字输入。不可用选项置灰并显示原因；能力不兼容的组合前端提示、服务端拒绝（错误信息指明缺少的能力）。
- 灰度创建：在解析器之外增加字段格式、分块器下拉，默认等于线上版本；服务端校验组合受支持且向量模型、维度与线上一致。灰度创建接口请求体新增可选 `indexSchema`、`chunkerVersion`，缺省取线上值。
- 版本列表、图构建页、灰度卡片显示字段格式的名称与 id，不再出现 `mapping vN` / `结构版本 vN`；图构建门槛提示改为「所选 Chunk 版本的字段格式 {label}（{id}）不支持实体关联，无法用于图构建」。

### 3.2 首次部署（`ElasticsearchIndexBootstrap`）

- 解析器规则不变：管理写操作已开启且多模态配置齐全（并存在兼容组合）时用 parse-2，否则 parse-1。
- 字段格式取 `defaults.index-schema`；建索引、校验、记录映射摘要均使用该插件；照常写入空库基线迁移记录。
- 采纳已有别名的路径：以默认字段格式校验；不匹配则按原有逻辑标记 NEEDS_ATTENTION，不写基线。

### 3.3 异常处理

| 情形 | 行为 |
|---|---|
| 版本引用未注册字段格式（如 `legacy-schema-*`） | 启动对账标记 `pipelineSupported=false`，管理端显示「字段格式 {id} 未注册」；读别名不受影响；不可写入、迁移、选择，只能清理 |
| 显式清单引用未注册字段格式 | 启动失败，指明清单项 |
| `defaults.index-schema` 未注册 | 启动失败 |
| 插件 id 重复 / 格式非法 | 启动失败，指明 id 与类名 |
| 新建版本 / 灰度组合能力不兼容 | 服务端 400/409，指明缺少的能力 |

## 4. 测试

- 注册表：id 重复、格式非法启动失败；`require` 未知 id 报错；`all()` 排序。
- `KwikiSchemaV1`：映射摘要等于原结构 3 参照值；父块 / 子块字段填充（含与不含图片标记、实体字段默认值）；`requiredFields()` 与映射一致。
- `MappingValidator`：缺少插件必需字段时报告字段名。
- 能力判断：图构建门槛；新建版本与灰度的不兼容组合被拒绝。
- 默认组合自动生成；显式清单引用未知字段格式启动失败；`defaults.index-schema` 生效。
- 首次部署：使用默认字段格式建索引并写基线。
- 迁移：`scripts/validate-migrations.sh` 通过；V37 回填规则（含 legacy 值）。
- 插件列表接口权限与返回结构；管理端表单下拉、默认值、不可用与不兼容提示；灰度创建三项选择。

## 5. 不在本次范围

- 解析器、分块器本身的插件化（子项目 2、3）。
- COMMUNITY 索引的结构版本。
- 外部 jar 动态加载插件。
