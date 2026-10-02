# 索引字段格式插件化 Implementation Plan（子项目 1/3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立公共插件框架，把 CHUNK 索引「结构版本」整数改为字符串 id 的字段格式插件（首个 `kwiki-schema-1` = 原结构 3），删除结构 1、2 及全部按数字分档的代码。

**Architecture:** 新包 `com.kwiki.indexing.plugin` 提供 `IndexPlugin` / `PluginRegistry` / `IndexCapability`；字段格式接口 `ChunkIndexSchema` 由 Spring Bean 实现，注册表按 id 解析。映射构建、文档填充、必需字段校验、图构建门槛全部委托插件或按能力判断。版本配置、构建清单、图构建表中的整数列改为字符串 id（V37 迁移）。

**Tech Stack:** Java 21、Spring Boot、Spring Data JPA、JdbcTemplate、Flyway(MySQL)、Elasticsearch Java Client；admin-frontend Vue 3 + TS + Element Plus；JUnit 5 + Mockito + AssertJ、Vitest。

**Spec:** `docs/superpowers/specs/2026-10-02-index-schema-plugin-design.md`

## Global Constraints

- 注释一律简体中文（AGENT.md §1）；`src/main/resources/db/migration/` 历史文件只读，只新增 `V37__...`（AGENT.md §2），新增后运行 `scripts/validate-migrations.sh`。
- 不引入 Docker / Testcontainers（AGENT.md §3）；不写真实密钥（AGENT.md §4）。
- 提交信息中文 conventional 风格，**不加 `Co-Authored-By` 行**。
- 单测：`./mvnw -q test -Dtest=<类名>`；全量：`./mvnw -q test`；前端：`cd admin-frontend && npm run build && npx vitest run`。
- 字段格式 id 正则：`^[a-z0-9][a-z0-9-]{1,62}$`。默认字段格式 `kwiki-schema-1`。
- `kwiki-schema-1` 在 1024 维下的映射摘要必须等于 `230402a7da44cfefc07f82e005d90e53d54f5fab557552c53add12a6c9de09c2`（即原 `ChunkMappingBuilder.mappingHash(1024, 3)`）。
- `community_index_version.mapping_schema_version` 及 COMMUNITY 相关代码不在范围内，不得修改。
- 现有环境将清空重建：迁移脚本只需保证全新部署正确，并在非空库上不失败。
- 本子项目完成后子项目 2、3 才能开始（它们修改同一批文件）。

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/java/com/kwiki/indexing/plugin/IndexPlugin.java` | 新建 | 插件公共接口 |
| `.../plugin/PluginAvailability.java` | 新建 | 可用性值对象 |
| `.../plugin/IndexCapability.java` | 新建 | 能力枚举 |
| `.../plugin/PluginRegistry.java` | 新建 | 通用注册表 |
| `.../indexing/schema/ChunkIndexSchema.java` | 新建 | 字段格式接口 |
| `.../indexing/schema/KwikiSchemaV1.java` | 新建 | 首个字段格式 |
| `.../indexing/schema/ChunkIndexSchemaRegistry.java` | 新建 | 字段格式注册表 Bean |
| `.../indexing/config/IndexingDefaultsProperties.java` | 新建 | `kwiki.indexing.defaults.*` |
| `ChunkMappingBuilder` / `MappingValidator` / `ChunkDocument` / `ElasticsearchIndexManager` | 改 | 委托插件，删除数字分档 |
| `EditableIndexConfig` / `SearchIndexVersion` / `BuildManifestSnapshot` / `IndexedVersion` / `IndexingProperties.Manifest` / `ResolvedPipeline` | 改 | `indexSchema` 字符串 |
| `VersionedIndexingPipelineRegistry` / `ParserCatalog` / `ElasticsearchIndexBootstrap` / `IndexVersionWriteService` / `SearchIndexValidationService` / `IndexingWorker` / `SearchIndexVersionService` / `SearchIndexAdminService` | 改 | 适配 |
| 图构建：`GraphBuildBatchRequest`、`GraphBuildRunCommand`、`GraphBuildRunRecord`、`GraphBuildRepository`、`JdbcGraphBuildRepository`、`GraphBuildTargets`、`GraphBuildBatchService`、`GraphSnapshotValidationService`、`GraphBuildAdminController` | 改 | `chunkIndexSchema` + 能力门槛 |
| `src/main/resources/db/migration/V37__index_schema_plugin_ids.sql` | 新建 | 列迁移 |
| `.../wiki/api/IndexPluginController.java` | 新建 | `GET /admin/search-indexes/plugins` |
| `SearchIndexAdminQueryService` / `SearchIndexGrayReleaseController` / `GrayReleaseService` | 改 | 视图字段、灰度三项选择 |
| `admin-frontend/src/{api.ts,views/IndexManagementView.vue,views/KnowledgeGraphView.vue,views/GrayReleaseView.vue,components/GrayReleaseCard.vue}` | 改 | 下拉选择与显示 |

（Java 路径前缀 `src/main/java/com/kwiki/`，测试对应 `src/test/java/com/kwiki/`。）

---

### Task 1: 公共插件框架

**Files:**
- Create: `src/main/java/com/kwiki/indexing/plugin/{IndexPlugin,PluginAvailability,IndexCapability,PluginRegistry}.java`
- Test: `src/test/java/com/kwiki/indexing/plugin/PluginRegistryTest.java`

**Interfaces（Produces）:**
- `IndexPlugin { String id(); String label(); default PluginAvailability availability() { return PluginAvailability.ok(); } }`
- `record PluginAvailability(boolean available, String unavailableReason) { static ok(); static unavailable(String reason); }`
- `enum IndexCapability { RESOURCE_IDS, ENTITY_LINKING }`
- `class PluginRegistry<T extends IndexPlugin> { PluginRegistry(String kind, List<T> plugins); Optional<T> find(String id); T require(String id); List<T> all(); }`

- [ ] **Step 1: 写失败测试**

```java
package com.kwiki.indexing.plugin;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class PluginRegistryTest {
    record P(String id, String label) implements IndexPlugin { }

    @Test
    void 按id查找并按id排序列出() {
        var registry = new PluginRegistry<>("字段格式", List.of(new P("b-2", "B"), new P("a-1", "A")));
        assertThat(registry.find("a-1")).isPresent();
        assertThat(registry.find("x")).isEmpty();
        assertThat(registry.all()).extracting(IndexPlugin::id).containsExactly("a-1", "b-2");
        assertThat(registry.require("b-2").label()).isEqualTo("B");
    }

    @Test
    void 未注册id抛出带类别与id的异常() {
        var registry = new PluginRegistry<>("字段格式", List.<P>of());
        assertThatThrownBy(() -> registry.require("legacy-schema-2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("字段格式").hasMessageContaining("legacy-schema-2");
    }

    @Test
    void id重复或格式非法启动失败() {
        assertThatThrownBy(() -> new PluginRegistry<>("字段格式", List.of(new P("a-1", "A"), new P("a-1", "B"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("a-1");
        assertThatThrownBy(() -> new PluginRegistry<>("字段格式", List.of(new P("Bad_Id", "A"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Bad_Id");
    }

    @Test
    void 默认可用() {
        assertThat(new P("a-1", "A").availability().available()).isTrue();
        assertThat(PluginAvailability.unavailable("缺少 X").unavailableReason()).isEqualTo("缺少 X");
    }
}
```

- [ ] **Step 2: 运行确认失败** — `./mvnw -q test -Dtest=PluginRegistryTest`，预期编译失败。

- [ ] **Step 3: 实现**

```java
// IndexPlugin.java
package com.kwiki.indexing.plugin;

/** 索引流水线插件（字段格式、解析器、分块器）的公共契约；实现类为 Spring Bean，按 id 注册。 */
public interface IndexPlugin {
    /** 全局唯一标识，持久化在索引版本配置中，一经使用不得更改语义。 */
    String id();

    /** 管理端显示名。 */
    String label();

    /** 当前部署下是否可用；不可用时原因不得包含密钥值。 */
    default PluginAvailability availability() {
        return PluginAvailability.ok();
    }
}
```

```java
// PluginAvailability.java
package com.kwiki.indexing.plugin;

/** 插件可用性：不可用时附带面向管理员的原因。 */
public record PluginAvailability(boolean available, String unavailableReason) {
    private static final PluginAvailability OK = new PluginAvailability(true, null);

    public static PluginAvailability ok() { return OK; }

    public static PluginAvailability unavailable(String reason) {
        return new PluginAvailability(false, reason);
    }
}
```

```java
// IndexCapability.java
package com.kwiki.indexing.plugin;

/** 字段格式提供、解析器需要的能力；兼容判断与图构建门槛据此进行。 */
public enum IndexCapability {
    /** 文档携带图片 contentIds。 */
    RESOURCE_IDS,
    /** 子块携带实体关联字段，可用于图构建。 */
    ENTITY_LINKING
}
```

```java
// PluginRegistry.java
package com.kwiki.indexing.plugin;

import java.util.*;
import java.util.regex.Pattern;

/** 同类插件的注册表：启动时校验 id 唯一与格式，运行时按 id 解析。 */
public class PluginRegistry<T extends IndexPlugin> {
    private static final Pattern ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,62}$");

    private final String kind;
    private final Map<String, T> byId = new TreeMap<>();

    public PluginRegistry(String kind, List<T> plugins) {
        this.kind = kind;
        for (T plugin : plugins) {
            String id = plugin.id();
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalStateException(kind + "插件 id 格式非法：" + id
                        + "（" + plugin.getClass().getName() + "）");
            }
            T previous = byId.putIfAbsent(id, plugin);
            if (previous != null) {
                throw new IllegalStateException(kind + "插件 id 重复：" + id + "（"
                        + previous.getClass().getName() + "、" + plugin.getClass().getName() + "）");
            }
        }
    }

    public Optional<T> find(String id) {
        return Optional.ofNullable(id == null ? null : byId.get(id));
    }

    public T require(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException(kind + "未注册：" + id));
    }

    public List<T> all() {
        return List.copyOf(byId.values());
    }

    public String kind() {
        return kind;
    }
}
```

- [ ] **Step 4: 运行通过** — `./mvnw -q test -Dtest=PluginRegistryTest`，预期 4 个用例 PASS。
- [ ] **Step 5: 提交** — `git add src/main/java/com/kwiki/indexing/plugin src/test/java/com/kwiki/indexing/plugin && git commit -m "feat(indexing): 新增索引流水线公共插件框架"`

---

### Task 2: 字段格式接口与 `kwiki-schema-1`

**Files:**
- Create: `src/main/java/com/kwiki/indexing/schema/{ChunkIndexSchema,KwikiSchemaV1,ChunkIndexSchemaRegistry}.java`
- Create: `src/main/java/com/kwiki/indexing/config/IndexingDefaultsProperties.java`
- Test: `src/test/java/com/kwiki/indexing/schema/KwikiSchemaV1Test.java`

**Interfaces（Produces）:**
- `ChunkIndexSchema extends IndexPlugin`：`Map<String,Object> mapping(int dims)`；`Set<String> requiredFields()`；`void decorateParent(Map<String,Object> doc, ParentChunk chunk, IndexedVersion version)`；`void decorateChild(Map<String,Object> doc, ChildChunk chunk, IndexedVersion version)`；`Set<IndexCapability> capabilities()`。
- `KwikiSchemaV1.ID = "kwiki-schema-1"`。
- `ChunkIndexSchemaRegistry extends PluginRegistry<ChunkIndexSchema>`（`@Component`，构造参数 `List<ChunkIndexSchema>`）；静态方法 `static String mappingHash(Map<String,Object> mapping)`（key 排序 JSON 的 SHA-256，与原 `ChunkMappingBuilder` 规范化方式一致）。
- `@ConfigurationProperties("kwiki.indexing.defaults") record IndexingDefaultsProperties(@DefaultValue("kwiki-schema-1") String indexSchema)`，在 `@EnableConfigurationProperties` 处登记（与 `IndexingProperties` 同处）。

注意：`IndexedVersion` 在 Task 3 才改为字符串字段；本任务 `decorate*` 只读取 `version.kbId()` 等已有字段与 `version.entityLinkingVersion()`。

- [ ] **Step 1: 写失败测试**

```java
package com.kwiki.indexing.schema;

import com.kwiki.indexing.plugin.IndexCapability;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class KwikiSchemaV1Test {
    private final KwikiSchemaV1 schema = new KwikiSchemaV1();

    @Test
    void 映射摘要与原结构3完全一致() {
        assertThat(ChunkIndexSchemaRegistry.mappingHash(schema.mapping(1024)))
                .isEqualTo("230402a7da44cfefc07f82e005d90e53d54f5fab557552c53add12a6c9de09c2");
    }

    @Test
    void 必需字段与映射字段一致且提供两项能力() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) ((Map<String, Object>) schema.mapping(1024).get("mappings")).get("properties");
        assertThat(props.keySet()).containsAll(schema.requiredFields());
        assertThat(schema.requiredFields()).contains("contentIds", "sourceChunkId", "entityIds",
                "entityLinkingVersion", "entityLinkingStatus", "vector");
        assertThat(schema.capabilities()).containsExactlyInAnyOrder(
                IndexCapability.RESOURCE_IDS, IndexCapability.ENTITY_LINKING);
        assertThat(schema.id()).isEqualTo("kwiki-schema-1");
    }

    @Test
    void 注册表拒绝未注册的默认字段格式() {
        var registry = new ChunkIndexSchemaRegistry(List.of(schema));
        assertThat(registry.require("kwiki-schema-1")).isSameAs(schema);
        assertThatThrownBy(() -> registry.require("legacy-schema-2")).hasMessageContaining("legacy-schema-2");
    }
}
```

文档填充（`decorateParent` / `decorateChild`）的断言放在 Task 3 的 `ChunkDocumentTest`（需要 `IndexedVersion` 新签名）。

- [ ] **Step 2: 运行确认失败** — `./mvnw -q test -Dtest=KwikiSchemaV1Test`。

- [ ] **Step 3: 实现**

`ChunkIndexSchema`：

```java
package com.kwiki.indexing.schema;

import com.kwiki.indexing.chunk.ChildChunk;
import com.kwiki.indexing.chunk.ParentChunk;
import com.kwiki.indexing.pipeline.IndexedVersion;
import com.kwiki.indexing.plugin.IndexCapability;
import com.kwiki.indexing.plugin.IndexPlugin;
import java.util.Map;
import java.util.Set;

/**
 * CHUNK 索引字段格式：决定物理索引映射、必需字段、文档特有字段与能力。
 * 修改索引字段 = 新建一个实现类，经新建版本 → 迁移 → 切换上线；已用于版本的实现不得修改字段。
 */
public interface ChunkIndexSchema extends IndexPlugin {
    /** 完整 ES 映射（含 dynamic: strict 与 dense_vector）。 */
    Map<String, Object> mapping(int embeddingDimensions);

    /** 校验物理索引时必须存在的字段。 */
    Set<String> requiredFields();

    /** 公共基础字段之外，父块的格式特有字段。 */
    void decorateParent(Map<String, Object> document, ParentChunk chunk, IndexedVersion version);

    /** 公共基础字段之外，子块的格式特有字段。 */
    void decorateChild(Map<String, Object> document, ChildChunk chunk, IndexedVersion version);

    Set<IndexCapability> capabilities();
}
```

`KwikiSchemaV1`：把原 `ChunkMappingBuilder.buildMapping(dims, 3)` 的属性构造原样迁入 `mapping()`（字段与顺序不变）；`requiredFields()` 返回原 `MappingValidator.REQUIRED_FIELDS_V3`；`decorateParent` = 原 `ChunkDocument.parent` 中的 `contentIds` 填充；`decorateChild` = 原 `baseChild` 的 `contentIds` + `addPendingEntityFields`（含 `sourceChunkId` 计算与 `entity-linking-v1` 缺省）。`resourceContentIds` 保留在 `ChunkDocument` 为公共静态方法供插件调用。`label()` 返回「图文 + 实体关联 v1」。类上 `@Component`。

`ChunkIndexSchemaRegistry`：

```java
package com.kwiki.indexing.schema;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.kwiki.indexing.plugin.PluginRegistry;
import org.springframework.stereotype.Component;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 字段格式注册表；映射摘要统一在此计算，插件不负责。 */
@Component
public class ChunkIndexSchemaRegistry extends PluginRegistry<ChunkIndexSchema> {
    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    public ChunkIndexSchemaRegistry(List<ChunkIndexSchema> schemas) {
        super("字段格式", schemas);
    }

    public String mappingHash(String schemaId, int embeddingDimensions) {
        return mappingHash(require(schemaId).mapping(embeddingDimensions));
    }

    public static String mappingHash(Map<String, Object> mapping) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(CANONICAL.writeValueAsString(mapping).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception failure) {
            throw new IllegalStateException("mapping hash failed", failure);
        }
    }
}
```

核对：原 `ChunkMappingBuilder.mappingHash` 的规范化若与上面不同（如编码或 writer 配置），以原实现为准并保持参照值相等。

`IndexingDefaultsProperties`：

```java
package com.kwiki.indexing.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 首次部署、新建版本、灰度使用的默认插件。 */
@ConfigurationProperties(prefix = "kwiki.indexing.defaults")
public record IndexingDefaultsProperties(@DefaultValue("kwiki-schema-1") String indexSchema) {
}
```

并新增启动校验：`ChunkIndexSchemaRegistry` 构造后（或单独 `@Component` 的 `ApplicationRunner`）若 `defaults.indexSchema` 未注册则抛 `IllegalStateException("默认字段格式未注册：…")`。

- [ ] **Step 4: 运行通过** — `./mvnw -q test -Dtest=KwikiSchemaV1Test`。
- [ ] **Step 5: 提交** — `git commit -m "feat(indexing): 新增字段格式插件接口与 kwiki-schema-1"`

---

### Task 3: 标识整数 → 字符串（原子切换，含数据库）

本任务改动面大但必须一次完成才能编译通过。以编译错误为导航：先改模型，再按 `./mvnw -q compile` 报错逐个修复。

**Files（主要）:**
- Create: `src/main/resources/db/migration/V37__index_schema_plugin_ids.sql`
- Modify: `EditableIndexConfig`、`SearchIndexVersion`、`BuildManifestSnapshot`、`IndexedVersion`、`IndexingProperties.Manifest`、`VersionedIndexingPipelineRegistry`（含 `ResolvedPipeline`）、`ChunkMappingBuilder`、`MappingValidator`、`ChunkDocument`、`ElasticsearchIndexManager`、`ElasticsearchIndexBootstrap`、`IndexVersionWriteService`、`SearchIndexValidationService`、`SearchIndexVersionService`、`SearchIndexAdminService`、`IndexingWorker`、`ChunkIndexRepository`、`ParserCatalog`、`BootstrapMigrationRecorder`（不变，构建清单自动带新字段）
- Test: `ChunkDocumentTest`（新建或扩充）、`MappingValidatorTest`、以及所有因签名变化需修改的测试

**Interfaces（Produces）:**
- `EditableIndexConfig(String parserVersion, String chunkerVersion, String embeddingProvider, String embeddingModel, Integer embeddingDimensions, @NotBlank String indexSchema)`
- `SearchIndexVersion#getIndexSchema()`；字段 `indexSchema` 映射列 `index_schema`
- `BuildManifestSnapshot(..., String indexSchema, String mappingHash)`
- `IndexedVersion(..., String indexSchema, String entityLinkingVersion)`（删除旧的 12 参兼容构造器）
- `IndexingProperties.Manifest(String id, String parserVersion, String chunkerVersion, String embeddingProfile, String embeddingModel, Integer dimensions, @NotBlank String indexSchema)`（配置键 `index-schema`）
- `ResolvedPipeline(..., String indexSchema, String entityLinkingVersion)`；`entityLinkingVersion` = 字段格式含 `ENTITY_LINKING` 时为 `entity-linking-v1`，否则 null
- `ElasticsearchIndexManager.createVersionedIndex(String name, int dims, String indexSchema)`、`validateIndex(String name, int dims, String indexSchema)`、`recreateOfflineVersion(String name, int dims, String indexSchema)`；删除不带 schema 的重载与按整数的重载
- `ChunkMappingBuilder.buildMapping(int dims, String indexSchema)`、`mappingHash(int dims, String indexSchema)`（委托注册表）；删除常量 `MAPPING_SCHEMA_MULTIMODAL`、`MAPPING_SCHEMA_ENTITY_LINKING` 及单参重载
- `MappingValidator.validate(mapping, dims, Set<String> requiredFields)`（删除按整数分档的重载与 `REQUIRED_FIELDS*` 常量）
- `ChunkDocument.parent(chunk, version, ChunkIndexSchema schema)`、`child(chunk, vector, version, ChunkIndexSchema schema)`（删除 `childWithEntityFields`；基础字段后调用 `schema.decorate*`）
- `VersionedIndexingPipelineRegistry.supportsCapability(EditableIndexConfig, IndexCapability)`（替代 `supportsEntityLinking`）；删除 `LATEST_MAPPING_SCHEMA_VERSION`
- `ParserCatalog`：删除 `latestConfigFor`；`configFor` 的排序改为「与基准 `indexSchema` 相同者优先，其次默认字段格式」
- `ElasticsearchIndexBootstrap`：字段格式取 `IndexingDefaultsProperties.indexSchema()`；`deployedConfig()` 的 `indexSchema` 同为默认值

- [ ] **Step 1: 新建迁移**

```sql
-- 字段格式插件化：结构版本整数改为插件 id。
-- 3 → kwiki-schema-1（字段完全一致）；其余历史值映射为 legacy-schema-<n>，
-- 无插件认领，运行时视为不受支持，只能清理。
ALTER TABLE search_index_version ADD COLUMN index_schema VARCHAR(64) NULL AFTER embedding_dimensions;
UPDATE search_index_version
SET index_schema = CASE mapping_schema_version
    WHEN 3 THEN 'kwiki-schema-1'
    ELSE CONCAT('legacy-schema-', mapping_schema_version) END;
ALTER TABLE search_index_version MODIFY COLUMN index_schema VARCHAR(64) NOT NULL;
ALTER TABLE search_index_version DROP COLUMN mapping_schema_version;

-- 图构建记录所用 CHUNK 版本的字段格式
ALTER TABLE graph_build_run ADD COLUMN chunk_index_schema VARCHAR(64) NULL;
UPDATE graph_build_run
SET chunk_index_schema = CASE mapping_schema_version
    WHEN 3 THEN 'kwiki-schema-1'
    ELSE CONCAT('legacy-schema-', mapping_schema_version) END;
ALTER TABLE graph_build_run MODIFY COLUMN chunk_index_schema VARCHAR(64) NOT NULL;
ALTER TABLE graph_build_run DROP COLUMN mapping_schema_version;

ALTER TABLE graph_snapshot ADD COLUMN chunk_index_schema VARCHAR(64) NULL;
UPDATE graph_snapshot
SET chunk_index_schema = CASE mapping_schema_version
    WHEN 3 THEN 'kwiki-schema-1'
    ELSE CONCAT('legacy-schema-', mapping_schema_version) END;
ALTER TABLE graph_snapshot MODIFY COLUMN chunk_index_schema VARCHAR(64) NOT NULL;
ALTER TABLE graph_snapshot DROP COLUMN mapping_schema_version;
```

先确认：`search_index_version` 中 `embedding_dimensions` 列名（V21）；`graph_build_run`、`graph_snapshot` 的 `mapping_schema_version` 是否被索引或约束引用（`grep -n "mapping_schema_version" src/main/resources/db/migration/V29__*.sql`），若被唯一键 / 索引引用，先 `DROP INDEX` 再按新列重建同名索引。运行 `scripts/validate-migrations.sh`。

注意：Task 3 仅改 `search_index_version` 相关代码；`graph_build_run` / `graph_snapshot` 列的 Java 适配在 Task 4。为保持本任务提交可启动，Task 3 与 Task 4 合并提交，或在 Task 3 中临时让图构建 JDBC 改读新列（推荐直接连同 Task 4 一起完成后再提交）。

- [ ] **Step 2: 写失败测试（文档与校验）**

`src/test/java/com/kwiki/indexing/search/ChunkDocumentTest.java`：

```java
@Test
void 子块按字段格式追加图片与实体字段_父块只追加图片() {
    var schema = new com.kwiki.indexing.schema.KwikiSchemaV1();
    String content = "<<KWIKI_META_DATA_START {\"type\":\"image\",\"contentId\":202}>>摘要<<KWIKI_META_DATA_END {\"type\":\"image\",\"contentId\":202}>>";
    // 用现有测试工具构造 ParentChunk / ChildChunk / IndexedVersion（indexSchema="kwiki-schema-1"）
    Map<String, Object> parent = ChunkDocument.parent(parentChunk(content), version(), schema);
    Map<String, Object> child = ChunkDocument.child(childChunk(content), new float[1024], version(), schema);
    assertThat(parent).containsEntry("contentIds", List.of(202L)).doesNotContainKey("entityIds");
    assertThat(child).containsEntry("contentIds", List.of(202L))
            .containsEntry("entityIds", List.of())
            .containsEntry("entityLinkingStatus", "PENDING")
            .containsKey("sourceChunkId");
}

@Test
void 不含图片的分块不写contentIds() { /* 同上，content 为纯文本，断言 doesNotContainKey("contentIds") */ }
```

`MappingValidatorTest`：用 `KwikiSchemaV1.mapping(1024)` 去掉 `entityIds` 后校验，断言返回 `"missing required field: entityIds"`；完整映射返回 null；维度不符返回 `vector dimension mismatch`。

- [ ] **Step 3: 改模型并按编译错误修复**

要点（逐项完成）：
1. `EditableIndexConfig`、`SearchIndexVersion`（字段 + getter + `editableConfig()` + `buildManifestSnapshot()` + 构造器）、`BuildManifestSnapshot`、`IndexedVersion`、`IndexingProperties.Manifest` 改为 `indexSchema`。
2. `ChunkMappingBuilder`：注入 `ChunkIndexSchemaRegistry`；`buildMapping(dims, schemaId)` = `registry.require(schemaId).mapping(dims)`；`mappingHash(dims, schemaId)` = `registry.mappingHash(...)`。保持 `@Component`；测试中以 `new ChunkMappingBuilder(new ChunkIndexSchemaRegistry(List.of(new KwikiSchemaV1())))` 构造。
3. `MappingValidator`：只保留按 `Set<String> requiredFields` 的两个 `validate` 重载；`ElasticsearchIndexManager.validateIndex(name, dims, schemaId)` 传入 `registry.require(schemaId).requiredFields()`。
4. `ElasticsearchIndexManager`：三个方法改为字符串 schema；删除原 `createVersionedIndex(name, dims)`、`recreateOfflineVersion(name, dims)`、`validateIndex(name, dims)` 及冲突分支中对 `MAPPING_SCHEMA_MULTIMODAL` 的特判。
5. `ChunkDocument`：`parent` / `child` 新签名，基础字段后调用 `schema.decorateParent/decorateChild`；删除 `childWithEntityFields`、`addPendingEntityFields`（已迁入插件）。`ChunkIndexRepository` 写入处从 `IndexedVersion.indexSchema()` 取插件（注入 `ChunkIndexSchemaRegistry`）。
6. `VersionedIndexingPipelineRegistry`：
   - 默认组合（未配显式清单）：遍历可用字段格式 `schemaRegistry.all()`（`availability().available()`），对 `kwiki-parse-1`（及多模态开启时的 `kwiki-parse-2`）× `kwiki-chunk-1` 生成组合；parse-2 需要 `RESOURCE_IDS`，组合仅在字段格式提供该能力时生成。id 形如 `implicit-<parser>-<schema>`。
   - `resolve`：额外要求 `schemaRegistry.find(config.indexSchema())` 存在且可用；`ResolvedPipeline.entityLinkingVersion` 按能力给出。
   - `supportsCapability(config, capability)`：`resolve(config).isPresent() && schema.capabilities().contains(capability)`。
   - 显式清单：启动时（构造或 `@PostConstruct`）校验每项 `indexSchema` 已注册，否则 `IllegalStateException("清单 {id} 引用未注册的字段格式 {schema}")`。
   - 删除 `LATEST_MAPPING_SCHEMA_VERSION`、`supportsEntityLinking`。
7. `IndexVersionWriteService.recreate`、`SearchIndexValidationService`（manifest 比对改用 `mappingHash(dims, indexSchema)`；`frozen.indexSchema()` 与版本一致）、`IndexingWorker`（`IndexedVersion` 构造传 `pipeline.indexSchema()`）、`SearchIndexVersionService`（`mappingHash` 计算）适配。
8. `ParserCatalog`：删除 `latestConfigFor` 与 `preferBaseSchema` 私有重载；排序为「`indexSchema` 等于基准者优先 → 等于 `defaults.indexSchema` 者其次」，返回首个受支持组合。
9. `ElasticsearchIndexBootstrap`：注入 `IndexingDefaultsProperties`（经现有 `setFirstDeploymentSupport` 增参或新 setter）；`deployedConfig()` 的字段格式 = 默认值；首次部署解析器选择逻辑不变，字段格式恒为默认值，`parsers.configFor(PARSER_VERSION_MULTIMODAL, base)` 取代 `latestConfigFor`；建索引 / 校验 / 哈希均用字符串 id。
10. `IndexPipelineSupportReconciler`：版本 `indexSchema` 未注册时 `pipelineSupportChanged(false)` 并写健康摘要「字段格式 {id} 未注册」。

- [ ] **Step 4: 编译并修复测试** — `./mvnw -q compile` 通过后运行 `./mvnw -q test`，逐个修复因签名变化失败的测试（`new EditableIndexConfig(..., 3)` → `"kwiki-schema-1"`；`new ChunkMappingBuilder()` → 带注册表构造；`mappingHash(1024)` 参照值测试删除或改为 Task 2 的参照值断言；`ElasticsearchIndexBootstrapTest` 中 fake 的 `createVersionedIndex(..., int)` 覆盖改为 `String`，记录 `create:<name>:<schema>`）。

- [ ] **Step 5: 与 Task 4 一并提交**（见 Task 4 Step 5）。

---

### Task 4: 图构建适配

**Files:**
- Modify: `graph/persistence/{GraphBuildBatchRequest,GraphBuildRunCommand,GraphBuildRunRecord,GraphBuildRepository,JdbcGraphBuildRepository,GraphBuildTargets,GraphBuildBatchService,GraphSnapshotValidationService}.java`、`wiki/api/GraphBuildAdminController.java`
- Test: `GraphBuildTargetsTest`、`GraphBuildBatchServiceTest`（或现有对应测试）、`GraphSnapshotValidationServiceTest`

**Interfaces:**
- 上述记录中的 chunk 侧 `int mappingSchemaVersion` → `String chunkIndexSchema`（`GraphBuildBatchRequest` 校验改为非空白）。
- `GraphBuildTargets`：删除 `MIN_MAPPING_SCHEMA_VERSION`；门槛 = `pipelines.supportsCapability(config, ENTITY_LINKING)`，失败信息「所选 Chunk 版本的字段格式 {label}（{id}）不支持实体关联，无法用于图构建」。
- `GraphBuildBatchService`：删除 `if (request.mappingSchemaVersion() < 3)`，改为同一能力判断。
- `JdbcGraphBuildRepository`：`graph_build_run` / `graph_snapshot` 读写 `chunk_index_schema`（`setString` / `rs.getString`）；**`allocateCommunityIndexVersion` 的 `mapping_schema_version` 属于 COMMUNITY，保持整数不动**。
- `GraphSnapshotValidationService.ConfigIdentity(String chunkIndexSchema, ...)`；诊断文本 `chunkIndexSchema=`。
- `GraphBuildAdminController`：删除请求体中已不采信的 `mappingSchemaVersion` 兼容字段；响应中 chunk 结构字段改名 `chunkIndexSchema`。

- [ ] **Step 1: 写失败测试** — `GraphBuildTargetsTest` 新增：版本字段格式不含 `ENTITY_LINKING` 时拒绝并含字段格式 id；含能力时通过（用 mock `VersionedIndexingPipelineRegistry.supportsCapability`）。
- [ ] **Step 2: 运行确认失败。**
- [ ] **Step 3: 实现上述改动**，`./mvnw -q compile`。
- [ ] **Step 4: 运行** `./mvnw -q test`，全部通过；`scripts/validate-migrations.sh` 通过。
- [ ] **Step 5: 提交（含 Task 3）**

```bash
git add -A src/main src/test
git commit -m "refactor(indexing): 结构版本整数改为字段格式插件 id，删除结构 1、2 与数字分档代码"
```

---

### Task 5: 插件列表接口、版本视图与灰度三项选择

**Files:**
- Create: `src/main/java/com/kwiki/wiki/api/IndexPluginController.java`
- Modify: `SearchIndexAdminQueryService`（`VersionView`）、`SearchIndexAdminService`（新建 / 编辑时兼容性校验）、`SearchIndexGrayReleaseController.CreateRequest`、`GrayReleaseService.create`、`ParserCatalog`（解析器选项适配）
- Test: `IndexPluginControllerTest`、`SearchIndexAdminServiceTest`、`GrayReleaseServiceTest`、`SearchIndexGrayReleaseControllerTest`

**Interfaces（Produces）:**
- `GET /api/v1/admin/search-indexes/plugins`（类级 `@PreAuthorize("hasRole('ADMIN')")`）返回：

```json
{
  "schemas":  [{"id":"kwiki-schema-1","label":"图文 + 实体关联 v1","available":true,"unavailableReason":null,"capabilities":["RESOURCE_IDS","ENTITY_LINKING"]}],
  "parsers":  [{"id":"kwiki-parse-1","label":"tika-v1","available":true,"unavailableReason":null,"requiredCapabilities":[]},
               {"id":"kwiki-parse-2","label":"pdfbox-v2","available":false,"unavailableReason":"缺少配置：KWIKI_VISION_API_KEY","requiredCapabilities":["RESOURCE_IDS"]}],
  "chunkers": [{"id":"kwiki-chunk-1","label":"父子分块 v1","available":true,"unavailableReason":null}],
  "defaults": {"indexSchema":"kwiki-schema-1"}
}
```

  本任务解析器由 `ParserCatalog.options()` 适配（parse-2 的 `requiredCapabilities` 固定 `RESOURCE_IDS`），分块器固定一项。
- `VersionView` 新增 `String indexSchemaLabel`、`List<String> schemaCapabilities`（未注册时 label 为 id、能力空列表）。
- `SearchIndexAdminService.createVersion/editVersion`：字段格式未注册或不可用 → `IllegalArgumentException("字段格式 {id} 未注册或不可用")`；解析器需要能力不被字段格式提供 → `IllegalArgumentException("字段格式 {id} 不提供解析器 {parser} 需要的能力 {cap}")`（经现有异常映射返回 400）。
- 灰度 `CreateRequest(String name, @NotBlank String parserVersion, String indexSchema, String chunkerVersion, @NotEmpty List<Long> kbIds)`；`GrayReleaseService.create(name, parserVersion, indexSchema, chunkerVersion, kbIds, operator)`：缺省取线上版本值；向量档案 / 模型 / 维度强制取线上；组合须 `pipelines.supports`，否则 `ConflictException` 说明原因。

- [ ] **Step 1: 写失败测试**（接口返回结构与权限；新建版本不兼容组合被拒；灰度不传 `indexSchema` 时取线上值、传入时使用传入值）。
- [ ] **Step 2–4: 实现并运行** `./mvnw -q test -Dtest='IndexPluginControllerTest,SearchIndexAdminServiceTest,GrayReleaseServiceTest,SearchIndexGrayReleaseControllerTest'`。
- [ ] **Step 5: 提交** — `git commit -m "feat(admin-api): 新增插件列表接口，新建版本与灰度支持选择字段格式与分块器"`

---

### Task 6: 管理端前端

**Files:**
- Modify: `admin-frontend/src/api.ts`、`src/views/IndexManagementView.vue`、`src/views/KnowledgeGraphView.vue`、`src/views/GrayReleaseView.vue`（创建对话框）、`src/components/GrayReleaseCard.vue`
- Test: `admin-frontend/tests/management.spec.ts`、`tests/gray-release.spec.ts`、新增 `tests/knowledge-graph-schema.spec.ts`（如已有图谱页测试则扩充）

要点：
1. `api.ts`：`Config.mappingSchemaVersion:number` → `indexSchema:string`；`Version` 增 `indexSchemaLabel:string; schemaCapabilities:string[]`；新增 `PluginCatalog` 类型与 `api.plugins()`；`createGrayRelease` 请求体增可选 `indexSchema`、`chunkerVersion`。**`CommunityVersion.mappingSchemaVersion` 不改。**
2. `IndexManagementView.vue`：加载时 `api.plugins()`；创建 / 编辑对话框中解析器、分块器、字段格式为 `el-select`，选项 `disabled` = `!available`，选项旁显示不可用原因；新建默认取线上版本配置，字段格式默认 `plugins.defaults.indexSchema`；删除结构版本数字输入与 `setParserVersion` 中的数字赋值；所选解析器需要能力而字段格式未提供时显示警示并禁用保存。版本列表显示 `indexSchemaLabel（indexSchema）` 替代 `mapping vN`。
3. `KnowledgeGraphView.vue`：`targetSchemaTooOld` 改为 `!targetVersion.schemaCapabilities.includes("ENTITY_LINKING")`；显示「字段格式 {label}（{id}）」；提示文案「所选 Chunk 版本的字段格式不支持实体关联，无法用于图构建」。
4. 灰度创建对话框：新增字段格式、分块器下拉，默认线上值；卡片显示字段格式名称。

- [ ] **Step 1: 写 / 改测试**：管理页渲染字段格式下拉且默认值正确；不兼容组合禁用保存；图谱页按能力显示阻断提示；灰度创建提交请求体包含所选 `indexSchema`。
- [ ] **Step 2–4: 实现并运行** `cd admin-frontend && npm run build && npx vitest run`，全部通过。
- [ ] **Step 5: 提交** — `git commit -m "feat(admin): 版本与灰度改为下拉选择插件，图构建按字段格式能力判断"`

---

### Task 7: 收尾验证与文档

- [ ] `grep -rn "mappingSchemaVersion\|MAPPING_SCHEMA_\|LATEST_MAPPING_SCHEMA_VERSION\|MIN_MAPPING_SCHEMA_VERSION" src/main/java admin-frontend/src frontend/src` 只允许出现在 COMMUNITY 相关代码（`community_index_version`、`CommunityVersion`、`allocateCommunityIndexVersion` 等）。
- [ ] `grep -rn ">= *3\|< *3\|>= *2" src/main/java/com/kwiki/indexing src/main/java/com/kwiki/graph` 中不再有针对字段格式的数字判断。
- [ ] 更新 `docs/multimodal-indexing.md`：结构版本段落改为字段格式插件说明（`kwiki-schema-1`、`kwiki.indexing.defaults.index-schema`、显式清单字段名 `index-schema`、新增字段格式 = 新建实现类）。
- [ ] `./mvnw -q test`、`scripts/validate-migrations.sh`、`cd admin-frontend && npm run build && npx vitest run` 全部通过。
- [ ] 提交 — `git commit -m "docs(indexing): 字段格式插件化收尾与文档"`
