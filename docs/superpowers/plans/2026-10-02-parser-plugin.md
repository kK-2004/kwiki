# 解析器插件化 Implementation Plan（子项目 2/3）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `kwiki-parse-1` / `kwiki-parse-2` 的散落分支收进 `DocumentParserPlugin` 实现类，由注册表驱动可用性、管理端选项、灰度与首次部署默认选择。

**Architecture:** 复用子项目 1 的 `IndexPlugin` / `PluginRegistry` / `IndexCapability`。worker、扫描器、校验、导入只面向插件接口；插件为 Spring Bean，自行注入 `DocumentParseService`、`MultimodalIndexingService` 等依赖。

**Tech Stack:** 同子项目 1。

**Spec:** `docs/superpowers/specs/2026-10-02-parser-plugin-design.md`

## Global Constraints

- **前置：子项目 1（`2026-10-02-index-schema-plugin.md`）已全部合入。** 不得与子项目 3 并行（改同一批文件）。
- 注释简体中文；不改历史迁移；不引入 Docker；不写密钥；提交信息中文 conventional，不加 `Co-Authored-By`。
- 插件 id 与现有持久化值保持一致：`kwiki-parse-1`、`kwiki-parse-2`（库中已有版本行引用，不得改名）。
- 行为不变：两个解析器对同一输入的结构化文档输出必须与改造前完全一致（以现有测试断言为基准迁移）。
- 单测 `./mvnw -q test -Dtest=<类名>`；全量 `./mvnw -q test`；前端 `cd admin-frontend && npm run build && npx vitest run`。

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/java/com/kwiki/indexing/parse/plugin/DocumentParserPlugin.java` | 新建 | 解析器接口 |
| `.../parse/plugin/{PageParseRequest,AttachmentParseRequest,ImportParseRequest}.java` | 新建 | 请求 record |
| `.../parse/plugin/TikaParserV1.java` | 新建 | `kwiki-parse-1` |
| `.../parse/plugin/PdfboxMultimodalParserV2.java` | 新建 | `kwiki-parse-2` |
| `.../parse/plugin/DocumentParserRegistry.java` | 新建 | 注册表 Bean |
| `IndexingDefaultsProperties` | 改 | 新增 `parsers` 偏好列表 |
| `IndexingWorker` | 改 | 页面 / 附件委托插件，删除常量与 `requireMultimodalFor` |
| `FixedRangeRebuildScanner` / `SearchIndexValidationService` | 改 | 附件类型来自插件 |
| `WikiImportDocumentParser` | 改 | 委托 `parseImport` |
| `MultimodalIndexingService` | 改 | 资产身份用插件 ID 常量 |
| `MultimodalSwitchReadiness` | 改 | 删除 `isMultimodal` / `requireReadyFor` |
| `AliasSwitchService` / `SearchIndexAdminQueryService` | 改 | 选择前检查解析器插件可用 |
| `ParserCatalog` | 改 | 注册表驱动 |
| `VersionedIndexingPipelineRegistry` | 改 | 解析器可用性、默认组合、兼容判断走注册表；`ResolvedPipeline.parser` 改类型 |
| `ElasticsearchIndexBootstrap` | 改 | 偏好列表选择解析器 |
| `IndexPluginController` | 改 | `parsers` 来自注册表 |

---

### Task 1: 接口、请求对象与注册表

**Files:** Create `parse/plugin/{DocumentParserPlugin,PageParseRequest,AttachmentParseRequest,ImportParseRequest,DocumentParserRegistry}.java`；Test `parse/plugin/DocumentParserRegistryTest.java`

**Interfaces（Produces）:**

```java
public interface DocumentParserPlugin extends IndexPlugin {
    Set<IndexCapability> requiredCapabilities();
    /** 除图片外可索引的附件类型（小写 MIME）。 */
    Set<String> indexableAttachmentTypes();
    StructuredDocument parsePage(PageParseRequest request);
    StructuredDocument parseAttachment(AttachmentParseRequest request);
    StructuredDocument parseImport(ImportParseRequest request);
}
public record PageParseRequest(WikiPage page, WikiPageRevision revision) { }
public record AttachmentParseRequest(Attachment attachment, java.util.function.Supplier<byte[]> content) { }
public record ImportParseRequest(long kbId, String fileName, String contentType, byte[] content) { }
@Component public class DocumentParserRegistry extends PluginRegistry<DocumentParserPlugin> {
    public DocumentParserRegistry(List<DocumentParserPlugin> parsers) { super("解析器", parsers); }
}
```

- [ ] 写测试：注册表收集两个 fake 插件、`require` 未知 id 抛出含「解析器」的异常。
- [ ] 实现、运行 `./mvnw -q test -Dtest=DocumentParserRegistryTest`、提交 `feat(indexing): 新增解析器插件接口与注册表`。

---

### Task 2: `TikaParserV1` 与 `PdfboxMultimodalParserV2`

**Files:** Create 两个实现类；Test `TikaParserV1Test`、`PdfboxMultimodalParserV2Test`

**`TikaParserV1`**（`@Component`，`ID = "kwiki-parse-1"`，label `tika-v1`）：
- `requiredCapabilities()` = 空；`indexableAttachmentTypes()` = 空集合。
- `parsePage` = `documentParseService.parse(page.getTitle() + ".md", "text/markdown", utf8(revision.getMarkdown()))`（原 `IndexingWorker.pageDocument` 非多模态分支）。
- `parseAttachment` 抛 `UnsupportedOperationException`（类型集合为空，worker 不会调用）。
- `parseImport` = `documentParseService.parse(fileName, contentType, new ByteArrayInputStream(content))`。

**`PdfboxMultimodalParserV2`**（`@Component`，`ID = "kwiki-parse-2"`，label `pdfbox-v2`）：
- 依赖：`DocumentParseService`、`MultimodalSwitchReadiness`、`ObjectProvider<MultimodalIndexingService>`、`ObjectProvider<SourceDocumentRepository>`、`AttachmentRepository`、`AttachmentStorage`。
- `availability()`：`readiness.ready()` 且多模态服务存在，否则 `unavailable("缺少配置：" + String.join("、", readiness.missingConfiguration()))`（多模态服务缺失时原因「多模态服务未启用」）。
- `requiredCapabilities()` = `{RESOURCE_IDS}`；`indexableAttachmentTypes()` = `{"application/pdf"}`。
- `parsePage`：迁入 `IndexingWorker.importedPdfDocument` 判断（`revisionNo == 1` 且 changeNote 以「导入 」开头，存在同库 STORED PDF 的 `DERIVED_FROM` 来源 → `buildPdfDocument`；来源无 contentCenterFileId → 抛 `AttachmentStorageException(PERMANENT, ...)`）；否则 `multimodal.buildPageDocument(kbId, pageId, revisionId, markdown)`。服务不可用时抛 `IllegalStateException`（保持原 fail-closed）。
- `parseAttachment`：`multimodal.buildPdfDocument(documentParseService, kbId, attachmentId, fileName, contentType, content.get())`。
- `parseImport`：非 PDF → 同 Tika；PDF → `documentParseService.parsePdfMultimodal(...)` 后仅拼接 `TextItem`（原 `WikiImportDocumentParser.parseWith` 逻辑）。不可用时抛 `IllegalStateException`（原 `requireReadyFor` 语义）。
- `MultimodalIndexingService` 中三处 `IndexingWorker.PARSER_VERSION_MULTIMODAL` 改为 `PdfboxMultimodalParserV2.ID`。

测试：把 `IndexingWorkerImportedPdfTest`、`WikiImportDocumentParserTest` 中针对解析行为的断言迁移为插件级测试（同输入、同输出、同异常）；可用性原因测试（缺 `KWIKI_VISION_API_KEY` 时原因包含该名）。

- [ ] 写测试 → 失败 → 实现 → `./mvnw -q test -Dtest='TikaParserV1Test,PdfboxMultimodalParserV2Test'` → 提交 `feat(indexing): 实现 kwiki-parse-1 与 kwiki-parse-2 解析器插件`。

---

### Task 3: 调用方改为面向插件

**Files:** Modify `IndexingWorker`、`FixedRangeRebuildScanner`、`SearchIndexValidationService`、`WikiImportDocumentParser`、`VersionedIndexingPipelineRegistry`；Tests 对应类

**Interfaces:**
- `ResolvedPipeline.parser` 类型改为 `DocumentParserPlugin`（原 `DocumentParseService`）。
- `VersionedIndexingPipelineRegistry`：注入 `DocumentParserRegistry`；`parserVersionSupported(id)` = 注册表存在且可用；默认组合遍历 `parserRegistry.all()` 中可用者 × 分块器 × 可用字段格式，且 `schema.capabilities().containsAll(parser.requiredCapabilities())`；`resolve` 同样检查能力兼容；删除 `SUPPORTED_PARSER_VERSION`。
- `IndexingWorker`：
  - 页面：`pipeline.parser().parsePage(new PageParseRequest(page, revision))`。
  - 附件：图片 → 公共 `imageDescriptorDocument`（不变）；否则 `pipeline.parser().indexableAttachmentTypes().contains(lower(contentType))` → `parseAttachment(new AttachmentParseRequest(attachment, () -> intermediates.attachmentBytes.computeIfAbsent(...)))`；否则删除分块。
  - 删除 `PARSER_VERSION`、`PARSER_VERSION_MULTIMODAL`、`CHUNKER_VERSION` 之外的解析相关常量引用（`CHUNKER_VERSION` 留给子项目 3）、`importedPdfDocument`、`requireMultimodalFor`、`isPdf`（若无其他引用）。
  - 外部引用 `IndexingWorker.PARSER_VERSION*` 的类改为 `TikaParserV1.ID` / `PdfboxMultimodalParserV2.ID`。
- `FixedRangeRebuildScanner` / `SearchIndexValidationService`：附件类型 = `{'image/png','image/jpeg','image/gif','image/webp'}` ∪ `parser.indexableAttachmentTypes()`，`parser = parserRegistry.require(version.editableConfig().parserVersion())`；SQL `IN (...)` 用占位符列表拼接（类型来自插件，仍需转义：只允许匹配 `^[a-z0-9.+/-]+$` 的类型）。
- `WikiImportDocumentParser`：确定解析器 id（原 `parserVersionFor`，缺省 `TikaParserV1.ID`）→ `parserRegistry.require(id).parseImport(new ImportParseRequest(kbId?, ...))`（全局入口 kbId 传 0）。

- [ ] 写 / 迁移测试：worker 附件分流（PDF 在 parse-1 下删除分块、parse-2 下调用插件）；扫描器 SQL 类型列表随解析器变化；导入走插件。
- [ ] 实现，`./mvnw -q compile`，`./mvnw -q test` 全通过，提交 `refactor(indexing): worker、扫描、校验与导入改为面向解析器插件`。

---

### Task 4: 可用性、目录、首次部署偏好

**Files:** Modify `MultimodalSwitchReadiness`、`AliasSwitchService`、`SearchIndexAdminQueryService`、`ParserCatalog`、`IndexingDefaultsProperties`、`ElasticsearchIndexBootstrap`、`IndexPluginController`、`IndexPipelineSupportReconciler`；Tests 对应类

**Interfaces:**
- `MultimodalSwitchReadiness`：删除 `isMultimodal`、`requireReadyFor`（保留 `ready()`、`missingConfiguration()` 供 parse-2 插件使用）。
- `AliasSwitchService.select` 与 `SearchIndexAdminQueryService.actionsFor`：`multimodalBlocked` 改为「`parserRegistry.find(parserVersion)` 不存在或不可用」，不可用原因进入 `migrateBlockedReason` / 选择拒绝信息。
- `ParserCatalog.options()`：`parserRegistry.all()` → `ParserOption(id, label, available && 清单受支持, reason)`；`label(String)` 静态方法改为实例方法查注册表（未注册返回 id）；`requireAvailable` 走插件可用性。
- `IndexingDefaultsProperties` 增 `@DefaultValue({"kwiki-parse-2","kwiki-parse-1"}) List<String> parsers`；启动校验：全部已注册、最后一项可用，否则启动失败。
- `ElasticsearchIndexBootstrap.firstDeploymentConfig()`：写操作关闭 → 列表最后一项；开启 → 第一个「可用且 `parsers.configFor(id, base)` 存在」的解析器；字段格式恒为默认值。
- `IndexPluginController.parsers` 来自注册表（含 `requiredCapabilities`）。
- `IndexPipelineSupportReconciler`：解析器未注册 → 「解析器 {id} 未注册」。

- [ ] 写测试：偏好列表规则三种情形；`ParserCatalog.options()` 来自注册表；不可用解析器的版本不可选择且原因可见；插件接口返回 `requiredCapabilities`。
- [ ] 实现、全量测试、提交 `feat(indexing): 解析器可用性、目录与首次部署偏好改由注册表驱动`。

---

### Task 5: 收尾

- [ ] `grep -rn "PARSER_VERSION_MULTIMODAL\|PARSER_VERSION\b\|isMultimodal\|requireReadyFor\|SUPPORTED_PARSER_VERSION" src/main/java` 无结果。
- [ ] 更新 `docs/multimodal-indexing.md`：parse-2 作为插件的启用条件与偏好配置 `kwiki.indexing.defaults.parsers`；新增解析器 = 新建 `DocumentParserPlugin` 实现类。
- [ ] `./mvnw -q test`、`cd admin-frontend && npm run build && npx vitest run` 通过；提交 `docs(indexing): 解析器插件化收尾`。
