# 多模态文档索引运维手册（Multimodal Document Indexing）

kwiki 在 Java 进程内原生完成 PDF 内嵌图片与发布后 Markdown 图片（手动上传
`attachment://uuid` 与绝对 HTTPS 外链）的解析：图片统一以 kFile 内容中心
`contentId` 为唯一持久身份，经 `qwen3.7-flash`（OpenAI-compatible
`/chat/completions`）生成面向检索的中文摘要，并在 Elasticsearch 索引投影
的原位置写入 `KWIKI_META_DATA` 受保护块。页面源 Markdown 与渲染行为不变；
不引入 MarkItDown、Python、FastAPI 或任何 OCR 服务。

## 1. 配置

所有开关经环境变量注入（见 `.env.example`），密钥绝不落盘：

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `KWIKI_MULTIMODAL_ENABLED` | `false` | 功能总开关；关闭时多模态装配不存在，kwiki-parse-2 流水线不可解析（fail closed） |
| `KWIKI_MULTIMODAL_PROMPT_VERSION` | `v1` | 摘要提示词版本；参与派生摘要持久身份，变更须经索引重建生效 |
| `KWIKI_MULTIMODAL_MAX_IMAGES_PER_DOCUMENT` | `20` | 单文档去重图片上限；超出显式失败而非截断 |
| `KWIKI_MULTIMODAL_MIN_IMAGE_PIXELS` | `1024` | 低于该像素数的装饰图片被过滤（计数入指标） |
| `KWIKI_MULTIMODAL_MAX_IMAGE_PIXELS` | `4194304` | 解码像素上限（压缩炸弹防护） |
| `KWIKI_MULTIMODAL_MAX_IMAGE_BYTES` | `10485760` | 单张派生图片字节上限 |
| `KWIKI_MULTIMODAL_MAX_SUMMARY_CHARS` | `512` | 摘要长度上限；超长按永久失败 |
| `KWIKI_MULTIMODAL_MAX_EXTERNAL_IMAGE_BYTES` | `10485760` | 外链响应字节上限 |
| `KWIKI_MULTIMODAL_EXTERNAL_IMAGE_TIMEOUT` | `20s` | 外链抓取总时限（含全部重定向） |
| `KWIKI_MULTIMODAL_EXTERNAL_IMAGE_MAX_REDIRECTS` | `3` | 重定向上限；每跳重新执行地址策略 |
| `KWIKI_MULTIMODAL_EXTERNAL_IMAGE_ALLOWED_PORTS` | `443` | 允许的外链 HTTPS 端口白名单 |
| `KWIKI_VISION_BASE_URL` / `KWIKI_VISION_API_KEY` | 空 | 视觉模型（OpenAI-compatible）；缺失时管理端显示原因并禁止创建或切换到 kwiki-parse-2 |
| `KWIKI_VISION_MODEL` | `qwen3.7-flash` | 模型名（与提示词版本共同构成摘要身份） |
| `KWIKI_VISION_TIMEOUT` / `KWIKI_VISION_CONNECT_TIMEOUT` | `60s` / `5s` | 视觉调用超时 |
| `KWIKI_VISION_MAX_RETRIES` / `KWIKI_VISION_CONCURRENCY` | `2` / `4` | 瞬时故障（429/5xx/超时）有界重试与并发信号量 |

视觉模型凭据与回答模型、embedding 完全解耦（独立容量与凭据生命周期）。
日志、异常、指标与用户可见错误一律脱敏：不含 API Key、完整 CDN URL、
外链 URL 或请求体。

## 2. 功能开关与索引版本（rollout）

多模态由 `PdfboxMultimodalParserV2` 解析器插件（id `kwiki-parse-2`）提供。
插件始终登记在 `DocumentParserRegistry` 中；仅当多模态总开关开启、视觉
模型配置齐全且多模态服务已装配时可用，否则返回脱敏的不可用原因。
CHUNK 索引字段格式由独立插件决定，
当前内置 `kwiki-schema-1`（「图文 + 实体关联 v1」）：保留原结构 3 的映射
与投影语义，提供 `RESOURCE_IDS`、`ENTITY_LINKING` 能力。父/子块按原文
标记提取去重 `contentIds`，子块包含实体关联字段；规范原文 `content`
仍保留完整标记，是资源字段可重建的事实来源。字段格式 id 是字符串，
不能按编号大小推断能力；COMMUNITY 索引仍使用独立的数字映射版本。

1. 默认关闭多模态。`kwiki-parse-1` 保留文本解析行为，字段格式默认使用
   `kwiki-schema-1`。历史 CHUNK 结构 1 / 2 版本登记为
   `legacy-schema-1` / `legacy-schema-2`，当前部署未注册这些插件，因此
   不可创建、重建或选为目标；不能仅更改 id 将旧映射当作新格式。
2. 配置 `KWIKI_MULTIMODAL_ENABLED=true`、`KWIKI_VISION_BASE_URL` 和
   `KWIKI_VISION_API_KEY` 后，不配置显式清单时，系统将每个可用字段格式
   与每个可用分块器配对，生成能力兼容的解析器组合；`kwiki-parse-2` 要求
   字段格式具有 `RESOURCE_IDS` 能力。向量配置沿用 `kwiki.qwen-embedding.*`。
   任一多模态必需配置缺失时，服务可启动并在管理端显示缺失项，但
   `kwiki-parse-2` 不可用；不会显示密钥值。
3. `kwiki.indexing.defaults.parsers` 是有序偏好列表，默认
   `[kwiki-parse-2, kwiki-parse-1]`。首次部署管理写操作关闭时使用最后一项
   作为基线；开启时按顺序选择首个可用且与默认字段格式存在受支持组合
   的解析器，且分块器固定为 `defaults.chunker`、向量配置固定为部署配置；
   没有完整受支持组合则失败关闭。列表必须非空、所有 id 必须已注册，
   最后一项必须可用，否则启动失败；基线应选择恒可用插件。
   `kwiki.indexing.defaults.index-schema` 默认为 `kwiki-schema-1`，用于
   首次部署和新建版本的默认字段格式；默认 id 未注册会阻止启动。
   `kwiki.indexing.defaults.chunker` 默认为 `kwiki-chunk-1`，未注册会阻止启动。
   修改默认值不会修改已有版本的配置或物理索引。管理端通过插件目录
   展示名称、id、能力和不可用原因；新建版本与灰度均可显式选择字段格式，
   灰度保留全局版本的向量模型和维度。
4. 如果部署使用显式 `kwiki.indexing.manifests`，系统不再补充默认组合。
   清单须准确登记解析器、分块器、向量档案、模型、维度和字段格式；字段名
   是 `index-schema`，不再使用 CHUNK 的 `mapping-schema-version`。
   插件存在且可用、解析器能力需求满足、完整清单匹配后，组合才可执行。
5. 经管理端（`KWIKI_INDEX_MANAGEMENT_MUTATIONS_ENABLED=true`）创建新
   版本或灰度 → 开启目标写入 → 存量迁移 → 校验 → 切换别名；
   存量文档只有显式重建后才携带图片语义，
   绝不静默混用新旧解析结果。部署配置预先加载后，已完成补齐、校验且
   当前流水线可执行的版本可以热切换读别名，无需重启；修改环境变量需
   重新加载应用配置。图构建另要求 `ENTITY_LINKING` 能力。
6. 开启多模态后：新上传的普通 PDF 附件进入索引队列；重建基线扫描
   （parse-2 版本）包含存量 STORED 普通 PDF。PDF 导入任务按当前选中
   的 parser 选择文本提取策略：parse-1 使用 Tika，parse-2 使用 PDFBox。
   导入页 Markdown 仍是可编辑的纯文本；导入成功并建立来源关联后，
   parse-2 对导入时发布的页面修订按原 PDF 顺序索引正文与图片，摘要和
   contentId 属于页面索引，因此沿用页面读取权限。导入来源附件本身
   不作为普通附件索引；后续手动编辑的修订按其已发布 Markdown 索引。
7. 提示词/模型变更会形成新的摘要身份并复用已上传 contentId；存量文档
   必须重建后才获得新摘要。若需要新解析代，应新增
   `DocumentParserPlugin` 实现并注册为 Spring Bean，再登记清单或使用默认
   组合；仅写入新清单 id 不会自动产生可执行解析器。

### 显式清单示例

下面只登记文本解析组合；启用多模态时须另加相同字段格式且
`parser-version: kwiki-parse-2` 的完整条目。模型和维度须与实际部署一致：

```yaml
kwiki:
  indexing:
    defaults:
      index-schema: kwiki-schema-1
      parsers: [kwiki-parse-2, kwiki-parse-1]
      chunker: kwiki-chunk-1
    manifests:
      - id: text-with-resources-and-entities
        parser-version: kwiki-parse-1
        chunker-version: kwiki-chunk-1
        embedding-profile: default
        embedding-model: ${KWIKI_QWEN_EMBEDDING_MODEL}
        dimensions: ${KWIKI_QWEN_EMBEDDING_DIMENSIONS}
        index-schema: kwiki-schema-1
```

### 新增字段格式

新增字段格式 = 新建 `ChunkIndexSchema` 实现类并注册为 Spring Bean。
实现须提供稳定且唯一的 `id`、显示名、可用性、完整 ES 映射、必需字段、
父/子块特有字段装饰和能力集合；`ChunkIndexSchemaRegistry` 自动收集实现，
统一计算映射摘要，映射构建、校验和文档投影通过该插件执行。
已被版本使用的实现不得修改字段或 id 语义；字段变化须新 id、新实现，
随后登记需要的显式清单（或使用默认组合），创建版本、开启写入、迁移、
校验、切换。
应覆盖映射与摘要稳定性、投影、能力门禁、未知/不可用插件及清单匹配测试。

### 新增解析器

新增解析器 = 新建 `DocumentParserPlugin` 实现类并注册为 Spring Bean；
`DocumentParserRegistry` 自动收集插件，目录、默认组合和执行调用统一使用注册表。
实现须提供稳定唯一的 `id`、显示名、可用性及脱敏原因、`requiredCapabilities`、
小写 MIME 集合 `indexableAttachmentTypes`，以及页面、非图片附件、导入文件的
解析方法。图片附件仍由公共元数据路径处理；附件字节通过请求中的 Supplier 按需读取。
字段格式必须包含解析器所需全部能力；默认组合只生成可用且能力兼容的组合。
显式清单仍须登记完整配置，且向量档案、模型、维度与运行环境一致。

上线新插件后，可按需加入 `defaults.parsers` 偏好列表，创建版本、开启写入、
存量迁移、校验、切换别名；已有版本不会自动更换解析器。已使用的 id 不得改变语义，行为变化
须使用新 id 和实现。测试应覆盖三个解析入口、附件类型分流、可用性诊断、
能力门禁、清单匹配及首次部署偏好。三类插件及分块器开发约定见
[索引插件开发与上线说明](index-plugins.md)。

### 回滚

应先将别名切回已重建、校验且当前部署可执行的 `kwiki-parse-1` 版本，
再关闭 `KWIKI_MULTIMODAL_ENABLED`；不要以未注册的历史字段格式作为回滚目标。
`kwiki-parse-2` 流水线随即不可解析（fail closed）。派生资产与摘要记录（
`derived_image_asset` / `derived_image_summary`）保留，供再次启用时复用；
回滚路径绝不删除内容中心对象。

## 3. 页面发布语义

- 摘要发生在**发布成功之后**的异步 PAGE 索引任务中；草稿保存不触发
  任何视觉调用，也不改变已发布索引。
- 发布修订的源 Markdown 永远不被修改；受保护块只存在于
  `StructuredDocument`/分块/ES 投影。
- `attachment://uuid` 仅在当前知识库范围内解析（STORED、字节嗅探为
  png/jpeg/gif/webp 且与声明一致），复用既有 `contentCenterFileId`，
  绝不二次上传；跨库、未存储或非图片引用使该次索引任务永久失败。
- 外链只接受绝对 HTTPS（默认仅 443 端口）：SSRF 防护拒绝
  loopback/私网/保留/CGNAT/云元数据/链路本地等全部非公网地址，
  每次重定向重新校验；不转发任何用户凭据。不支持的 scheme
  （http、data URI、相对地址）显式失败，不做 alt 文本降级。
- 同一 URL 内容变化（字节哈希不同）产生新的派生身份与新摘要；
  旧修订仍可由其镜像 contentId 完整重建。

## 4. 失败分类与幂等

- 任何图片阶段（解析/抓取/上传/摘要）失败：本次不提交部分多模态
  版本；已完成的中间结果（contentId、READY 摘要）持久化供重试复用。
- 瞬时（远端 429/5xx、超时、传输错误）：按 worker 有界指数退避重试。
- 永久（非重试 4xx、非图片字节、超限、策略拒绝、空/违规摘要）：
  立即死信，需修正文档或配置后重新入队。
- 并发索引同一源：数据库唯一约束（源身份 + 解析器版本 + 图片哈希）
  保证只有一个执行者上传/摘要，其余收敛到同一 contentId。

## 5. 监控

`/actuator/metrics`：

- `kwiki_multimodal_images_total{stage=extracted|filtered-mask|filtered-small|
  filtered-large|filtered-corrupt|deduplicated|uploaded|reused|summarized}`
- `kwiki_multimodal_vision_calls_total{outcome=ok|invalid-response|rejected|
  transient-exhausted}` 与 `kwiki_multimodal_vision_latency`
- `kwiki_multimodal_ssrf_denies_total{reason=...}`
- `kwiki_multimodal_oversized_blocks_total`（超限受保护块单独成块）
- `kwiki_multimodal_failures_total{stage=...,classification=...}`
- `kwiki_indexing_jobs_total{outcome=transient-multimodal-failure|
  permanent-multimodal-failure}`

上线前连通性检查：内容中心可达（附件上传/CDN 链接签发）、
`KWIKI_VISION_BASE_URL` 的 `/chat/completions` 可用 `qwen3.7-flash`、
外链下载器到目标图源的 443 出口畅通。

## 6. 派生资源清理的当前限制

kFile SDK 现已支持按 contentId 删除。回收站在最后一个附件引用被物理清除时
会同步删除对应文件；但派生图片仍可能被活跃索引版本引用，因此该清理器不做
猜测性删除：

- `DerivedImageCleanupService` 只把"源已消失"（附件归档/删除、修订消失）
  的资产行标记为 `ORPHAN_CANDIDATE` 并计入
  `kwiki_multimodal_cleanup_candidates_total`，供审计。
- 后续实现需在确认没有任何活跃索引版本引用后，再删除派生图片文件。
