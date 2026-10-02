# 分块器插件化 Implementation Plan（子项目 3/3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把唯一分块器 `kwiki-chunk-1` 改为 `ChunkerPlugin` 实现类 + 注册表，删除写死的 `SUPPORTED_CHUNKER_VERSION` / `CHUNKER_VERSION` 与流水线中直接构造的父子分块器。

**Architecture:** 复用公共插件框架；`ResolvedPipeline` 持有 `ChunkerPlugin`；默认分块器由 `kwiki.indexing.defaults.chunker` 指定。

**Tech Stack:** 同子项目 1。

**Spec:** `docs/superpowers/specs/2026-10-02-chunker-plugin-design.md`

## Global Constraints

- **前置：子项目 1、2 已全部合入。**
- 注释简体中文；不改历史迁移；不引入 Docker；不写密钥；提交信息中文 conventional，不加 `Co-Authored-By`。
- 插件 id 保持 `kwiki-chunk-1`（库中已有版本行引用）。
- 分块输出（key、ordinal、字符位置、内容）与改造前逐字一致。
- 单测 `./mvnw -q test -Dtest=<类名>`；全量 `./mvnw -q test`。

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/java/com/kwiki/indexing/chunk/plugin/ChunkerPlugin.java` | 新建 | 接口 |
| `.../chunk/plugin/KwikiChunkV1.java` | 新建 | `kwiki-chunk-1` |
| `.../chunk/plugin/ChunkerRegistry.java` | 新建 | 注册表 Bean |
| `IndexingDefaultsProperties` | 改 | 新增 `chunker` |
| `VersionedIndexingPipelineRegistry`（含 `ResolvedPipeline`） | 改 | 分块器走注册表 |
| `IndexingWorker` | 改 | 调用插件分块，删除 `CHUNKER_VERSION` |
| `ElasticsearchIndexBootstrap` | 改 | 默认分块器取配置 |
| `IndexPluginController` / `IndexPipelineSupportReconciler` | 改 | 列表与对账 |

---

### Task 1: 接口、实现与注册表

**Interfaces（Produces）:**

```java
public interface ChunkerPlugin extends IndexPlugin {
    List<ParentChunk> parents(String keyPrefix, StructuredDocument document);
    List<ChildChunk> children(ParentChunk parent, StructuredDocument document);
}

@Component
public class KwikiChunkV1 implements ChunkerPlugin {
    public static final String ID = "kwiki-chunk-1";
    private final ParentChunker parentChunker;
    private final ChildChunker childChunker;

    public KwikiChunkV1(org.springframework.beans.factory.ObjectProvider<com.kwiki.indexing.multimodal.MultimodalMetrics> metrics) {
        var m = metrics.getIfAvailable();
        this.parentChunker = new ParentChunker(ChunkingConfig.defaults(), m);
        this.childChunker = new ChildChunker(ChunkingConfig.defaults(), m);
    }
    public String id() { return ID; }
    public String label() { return "父子分块 v1"; }
    public List<ParentChunk> parents(String keyPrefix, StructuredDocument d) { return parentChunker.chunk(keyPrefix, d); }
    public List<ChildChunk> children(ParentChunk p, StructuredDocument d) { return childChunker.chunk(p, d); }
}

@Component
public class ChunkerRegistry extends PluginRegistry<ChunkerPlugin> {
    public ChunkerRegistry(List<ChunkerPlugin> chunkers) { super("分块器", chunkers); }
}
```

（`ParentChunker` / `ChildChunker` 的 metrics 构造参数类型以现有代码为准。）

- [ ] **Step 1: 写失败测试** `KwikiChunkV1Test`：对 `ParentChildChunkerTest`、`ProtectedBlockChunkingTest` 中的同一输入文档，`KwikiChunkV1` 产出的父块、子块列表与直接 `new ParentChunker(ChunkingConfig.defaults(), null)` / `ChildChunker` 产出逐项相等（key、ordinal、charStart、charEnd、content）。
- [ ] **Step 2–4:** 实现并运行 `./mvnw -q test -Dtest=KwikiChunkV1Test`。
- [ ] **Step 5:** 提交 `feat(indexing): 新增分块器插件接口与 kwiki-chunk-1`。

---

### Task 2: 流水线与 worker 改用插件

**Interfaces:**
- `ResolvedPipeline`：删除 `ParentChunker parentChunker`、`ChildChunker childChunker`，新增 `ChunkerPlugin chunker`。
- `VersionedIndexingPipelineRegistry`：注入 `ChunkerRegistry`；`resolve` 中 `chunkerRegistry.find(config.chunkerVersion())` 存在且可用，否则 `Optional.empty()`；默认组合遍历可用分块器；清单匹配不再与常量比较；删除 `SUPPORTED_CHUNKER_VERSION`。
- `IndexingWorker`：`pipeline.parentChunker().chunk(...)` → `pipeline.chunker().parents(...)`，子块同理；删除 `CHUNKER_VERSION` 常量，外部引用改为 `KwikiChunkV1.ID`。

- [ ] 写测试：`VersionedIndexingPipelineRegistryTest` 新增「未注册分块器的配置不受支持」；`IndexingWorkerTest` 现有用例在新结构下通过。
- [ ] 实现，`./mvnw -q compile`、`./mvnw -q test` 通过，提交 `refactor(indexing): 流水线与 worker 改为面向分块器插件`。

---

### Task 3: 默认分块器、目录与对账

**Interfaces:**
- `IndexingDefaultsProperties` 增 `@DefaultValue("kwiki-chunk-1") String chunker`；启动校验已注册。
- `ElasticsearchIndexBootstrap.deployedConfig()` 的分块器 = `defaults.chunker()`。
- `IndexPluginController.chunkers` 来自 `ChunkerRegistry`（`id`、`label`、`available`、`unavailableReason`）。
- `IndexPipelineSupportReconciler`：分块器未注册 → 「分块器 {id} 未注册」。

- [ ] 写测试：默认值生效、未注册启动失败；插件接口分块器列表来自注册表。
- [ ] 实现、全量测试、提交 `feat(indexing): 默认分块器配置与分块器目录改由注册表驱动`。

---

### Task 4: 收尾

- [ ] `grep -rn "CHUNKER_VERSION\|SUPPORTED_CHUNKER_VERSION\|new ParentChunker\|new ChildChunker" src/main/java` 仅出现在 `KwikiChunkV1`。
- [ ] 在 `docs/multimodal-indexing.md`（或新建 `docs/index-plugins.md`）补充：三类插件的接口、注册方式、默认配置 `kwiki.indexing.defaults.{index-schema,parsers,chunker}`、新增插件后的上线流程（新建版本 / 灰度 → 开启写入 → 存量迁移 → 校验 → 切换）。
- [ ] `./mvnw -q test`、`cd admin-frontend && npm run build && npx vitest run` 通过；提交 `docs(indexing): 分块器插件化收尾与插件开发说明`。
