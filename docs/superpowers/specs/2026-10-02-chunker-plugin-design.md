# 设计：分块器插件化（子项目 3/3）

日期：2026-10-02
前置：子项目 1、2 已合入——复用公共插件框架、插件列表接口、灰度三项选择与默认偏好配置方式。

## 1. 目标

把唯一的分块器 `kwiki-chunk-1`（`IndexingWorker.CHUNKER_VERSION` 常量 + `VersionedIndexingPipelineRegistry` 中写死的 `SUPPORTED_CHUNKER_VERSION` 与 `new ParentChunker(ChunkingConfig.defaults(), …)`）改为「接口 + 实现类 + 注册表」。新增分块策略（如不同父子块尺寸、按语义切分）= 新建一个实现类，经新建版本 / 灰度 → 迁移 → 切换上线。

## 2. 插件模型

### 2.1 接口 `ChunkerPlugin extends IndexPlugin`（包 `com.kwiki.indexing.chunk.plugin`）

```java
public interface ChunkerPlugin extends IndexPlugin {
    /** 结构化文档 → 父块（较大段落，用于补全上下文）。 */
    List<ParentChunk> parents(String keyPrefix, StructuredDocument document);

    /** 单个父块 → 子块（参与检索与向量化）。 */
    List<ChildChunk> children(ParentChunk parent, StructuredDocument document);
}
```

分块器与字段格式、解析器无能力约束（`requiredCapabilities` 不引入，YAGNI）。父子块的 key、ordinal、字符位置等契约保持现有 `ParentChunk` / `ChildChunk` 不变，保证同一资源重放时文档 id 稳定。

### 2.2 实现 `KwikiChunkV1`

- id `kwiki-chunk-1`，显示名「父子分块 v1」，恒可用。
- 内部持有 `new ParentChunker(ChunkingConfig.defaults(), metrics)`、`new ChildChunker(ChunkingConfig.defaults(), metrics)`（metrics 为可选的多模态指标，同现状），行为不变。

### 2.3 注册表与调用方改造

- `ChunkerRegistry extends PluginRegistry<ChunkerPlugin>`。
- `ResolvedPipeline`：`ParentChunker parentChunker` + `ChildChunker childChunker` 合并为 `ChunkerPlugin chunker`；`IndexingWorker` 改调 `pipeline.chunker().parents(...)` / `children(...)`。
- `VersionedIndexingPipelineRegistry`：删除 `SUPPORTED_CHUNKER_VERSION`；`resolve` / 默认组合 / 清单匹配中的分块器校验改为注册表查找 + 可用性。
- 删除 `IndexingWorker.CHUNKER_VERSION`；`ElasticsearchIndexBootstrap.deployedConfig()` 的分块器取 `kwiki.indexing.defaults.chunker`。
- 新增配置 `kwiki.indexing.defaults.chunker`，默认 `kwiki-chunk-1`；未注册 → 启动失败。
- 插件列表接口 `chunkers` 改由注册表提供；新建版本、灰度的分块器下拉默认线上值（子项目 1 已提供 UI）。

## 3. 异常处理

| 情形 | 行为 |
|---|---|
| 版本引用未注册分块器 | 启动对账标记 `pipelineSupported=false`，显示「分块器 {id} 未注册」 |
| `defaults.chunker` 未注册 | 启动失败 |

## 4. 测试

- `KwikiChunkV1` 对同一文档的父子块输出与改造前完全一致（沿用 `ParentChildChunkerTest`、`ProtectedBlockChunkingTest` 的输入断言）。
- worker 通过插件分块；流水线解析未知分块器返回不受支持。
- `defaults.chunker` 生效与未注册时启动失败。
- 插件列表接口返回分块器注册表内容。

## 5. 不在本次范围

新增第二种分块策略。
