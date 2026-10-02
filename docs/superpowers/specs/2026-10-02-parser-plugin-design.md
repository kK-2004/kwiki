# 设计：解析器插件化（子项目 2/3）

日期：2026-10-02
前置：子项目 1（`2026-10-02-index-schema-plugin-design.md`）已合入——复用其公共插件框架（`IndexPlugin`、`PluginRegistry`、`PluginAvailability`、`IndexCapability`）、插件列表接口与灰度三项选择。

## 1. 目标

把 `kwiki-parse-1` / `kwiki-parse-2` 从「`IndexingWorker` 中的字符串常量 + 各处 `if (PARSER_VERSION_MULTIMODAL.equals(...))` 分支」改为「接口 + 实现类 + 注册表」。新增解析器 = 新建一个实现类；可用性、管理端选项、灰度、默认选择全部由注册表驱动。

## 2. 现状：解析器分支分布

| 文件 | 分支内容 |
|---|---|
| `IndexingWorker` | 页面文档（导入 PDF 首修订走原 PDF、多模态 Markdown、纯 Markdown）；附件文档（PDF 仅 parse-2）；`requireMultimodalFor` |
| `FixedRangeRebuildScanner` | parse-2 才把 PDF 附件纳入扫描 |
| `SearchIndexValidationService` | 同上，覆盖率计算的附件类型 |
| `WikiImportDocumentParser` | 导入时按选中解析器决定 PDF 走 PDFBox 还是 Tika |
| `MultimodalIndexingService` | 派生图片资产身份里写死 parse-2 |
| `MultimodalSwitchReadiness` | `isMultimodal(parserVersion)` |
| `ParserCatalog` | 标签表、可用性、灰度选项 |
| `VersionedIndexingPipelineRegistry` | `parserVersionSupported`、默认组合 |
| `ElasticsearchIndexBootstrap` | 首次部署选 parse-2 / parse-1 |

## 3. 插件模型

### 3.1 接口 `DocumentParserPlugin extends IndexPlugin`（包 `com.kwiki.indexing.parse.plugin`）

```java
public interface DocumentParserPlugin extends IndexPlugin {
    /** 该解析器产出的文档要求字段格式提供的能力（如产出图片受保护块需要 RESOURCE_IDS）。 */
    Set<IndexCapability> requiredCapabilities();

    /** 除图片外，该解析器能索引的附件内容类型（小写 MIME）。图片附件由公共元数据描述处理，与解析器无关。 */
    Set<String> indexableAttachmentTypes();

    /** 页面已发布修订 → 结构化文档。 */
    StructuredDocument parsePage(PageParseRequest request);

    /** 非图片附件 → 结构化文档；仅对 indexableAttachmentTypes 内的类型调用。 */
    StructuredDocument parseAttachment(AttachmentParseRequest request);

    /** 导入文件 → 结构化文档（导入页正文的生成）。 */
    StructuredDocument parseImport(ImportParseRequest request);
}
```

请求对象（record）：

- `PageParseRequest(WikiPage page, WikiPageRevision revision)`
- `AttachmentParseRequest(Attachment attachment, Supplier<byte[]> content)`：字节按需读取，沿用 worker 按 job 缓存。
- `ImportParseRequest(long kbId, String fileName, String contentType, byte[] content)`

插件是 Spring Bean，自行注入所需依赖（`DocumentParseService`、`MultimodalIndexingService`、`SourceDocumentRepository`、`AttachmentRepository`、`AttachmentStorage` 等），worker 不再感知解析器内部差异。

### 3.2 实现

| 类 | id | 显示名 | 需要能力 | 附件类型 | 可用性 |
|---|---|---|---|---|---|
| `TikaParserV1` | `kwiki-parse-1` | tika-v1 | 无 | 无（仅图片走公共路径） | 恒可用 |
| `PdfboxMultimodalParserV2` | `kwiki-parse-2` | pdfbox-v2 | `RESOURCE_IDS` | `application/pdf` | `MultimodalSwitchReadiness.ready()` 且多模态服务存在；否则不可用并给出缺失配置名 |

行为与现状逐一对应：

- `TikaParserV1.parsePage`：`DocumentParseService.parse(title + ".md", "text/markdown", markdown)`。
- `PdfboxMultimodalParserV2.parsePage`：导入 PDF 首修订（`revisionNo == 1` 且 changeNote 以「导入 」开头、存在同库 STORED PDF 的 `DERIVED_FROM` 来源）→ `buildPdfDocument`；否则 `MultimodalIndexingService.buildPageDocument`。
- `PdfboxMultimodalParserV2.parseAttachment`：`buildPdfDocument`。
- `parseImport`：parse-1 → `DocumentParseService.parse`；parse-2 → 对 PDF 走多模态抽取并组装（现 `WikiImportDocumentParser` 逻辑原样迁入），非 PDF 同 parse-1。
- 派生图片资产身份中的解析器版本改用 `PdfboxMultimodalParserV2.ID`。

### 3.3 注册表与调用方改造

- `DocumentParserRegistry extends PluginRegistry<DocumentParserPlugin>`。
- `IndexingWorker`：图片附件 → 公共元数据描述；其他附件 → `parser.indexableAttachmentTypes()` 包含该类型则 `parser.parseAttachment`，否则删除该资源的分块；页面 → `parser.parsePage`。删除 `requireMultimodalFor`、`PARSER_VERSION*` 常量。
- `FixedRangeRebuildScanner`、`SearchIndexValidationService`：附件类型 = 图片类型 ∪ `parser.indexableAttachmentTypes()`。
- `WikiImportDocumentParser`：取选中解析器（全局线上版本或知识库所属灰度）→ `parser.parseImport`。
- `ResolvedPipeline`：`DocumentParseService parser` 改为 `DocumentParserPlugin parser`。
- `VersionedIndexingPipelineRegistry`：`parserVersionSupported` → `registry.find(id).map(p -> p.availability().available())`；默认组合遍历解析器注册表；兼容判断 = 字段格式能力 ⊇ 解析器需要能力。
- `MultimodalSwitchReadiness`：删除 `isMultimodal`、`requireReadyFor`；切换别名、选择版本前统一检查「版本解析器插件可用」（不可用原因来自插件）。`ready()` / `missingConfiguration()` 仅供 parse-2 插件内部计算可用性。
- `ParserCatalog`：删除标签表与写死的两项；`options()` 来自注册表（`id`、`label`、`availability`）；`requireAvailable` 走插件可用性；`configFor` 保留（清单匹配）。
- 插件列表接口的 `parsers` 改由注册表提供（含 `requiredCapabilities`）。

### 3.4 首次部署默认解析器

新增 `kwiki.indexing.defaults.parsers`（有序偏好列表，默认 `[kwiki-parse-2, kwiki-parse-1]`）：

- 管理写操作未开启 → 用列表最后一项（基线解析器）。
- 已开启 → 取列表中第一个「插件可用且与默认字段格式能力兼容、存在受支持组合」的解析器。
- 列表最后一项必须恒可用，否则启动失败。

与此前规则等价：开启写操作且多模态配置齐全 → parse-2，否则 parse-1。

## 4. 异常处理

| 情形 | 行为 |
|---|---|
| 版本引用未注册解析器 | 启动对账标记 `pipelineSupported=false`，显示「解析器 {id} 未注册」 |
| 解析器插件不可用（如缺视觉配置） | 已有该解析器的版本写入失败重试并在管理端显示原因；不可选为线上、不可新建 / 灰度 |
| `defaults.parsers` 含未注册 id 或最后一项不可用 | 启动失败 |
| 解析器需要的能力字段格式不提供 | 新建版本 / 灰度被拒绝，指明缺少的能力 |

## 5. 测试

- 两个插件的行为回归：沿用并迁移 `IndexingWorkerTest`、`IndexingWorkerImportedPdfTest`、`WikiImportDocumentParserTest`、`MultimodalEndToEndTest` 中的断言到插件级测试。
- worker：附件按插件类型分流；不可索引类型删除分块；页面委托插件。
- 扫描器、校验覆盖率的附件类型来自插件。
- 注册表驱动的 `ParserCatalog.options()`、可用性原因。
- 默认解析器偏好规则（写操作关 / 开、parse-2 不可用回退）。
- 能力不兼容组合被拒绝。

## 6. 不在本次范围

分块器插件化（子项目 3）；新增第三种解析器。
