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
| `KWIKI_VISION_BASE_URL` / `KWIKI_VISION_API_KEY` | 空 | 视觉模型（OpenAI-compatible）；**启用多模态时必填**，启动期校验失败即拒绝启动 |
| `KWIKI_VISION_MODEL` | `qwen3.7-flash` | 模型名（与提示词版本共同构成摘要身份） |
| `KWIKI_VISION_TIMEOUT` / `KWIKI_VISION_CONNECT_TIMEOUT` | `60s` / `5s` | 视觉调用超时 |
| `KWIKI_VISION_MAX_RETRIES` / `KWIKI_VISION_CONCURRENCY` | `2` / `4` | 瞬时故障（429/5xx/超时）有界重试与并发信号量 |

视觉模型凭据与回答模型、embedding 完全解耦（独立容量与凭据生命周期）。
日志、异常、指标与用户可见错误一律脱敏：不含 API Key、完整 CDN URL、
外链 URL 或请求体。

## 2. 功能开关与索引版本（rollout）

多模态属于新的解析代 `kwiki-parse-2`，配合 mapping schema v2
（chunk 文档新增去重 `contentIds` 数组，规范原文 `content` 保持完整标记，
仍是资源字段可重建的事实来源）：

1. 默认关闭。旧版本（`kwiki-parse-1` / mapping v1）行为完全不变，
   旧物理索引继续可读。
2. 开启 `KWIKI_MULTIMODAL_ENABLED=true` 并提供 `KWIKI_VISION_*` 后，
   运维在 `kwiki.indexing.manifests` 中登记
   `parser-version: kwiki-parse-2`、`mapping-schema-version: 2` 的结构代。
3. 经管理端（`KWIKI_INDEX_MANAGEMENT_MUTATIONS_ENABLED=true`）创建新
   版本、重建、校验、切换别名——存量文档只有显式重建后才携带图片语义，
   绝不静默混用新旧解析结果。
4. 开启多模态后：新上传的 PDF 附件进入索引队列；重建基线扫描
   （parse-2 版本）包含存量 STORED PDF。
5. 提示词/模型变更：修改 `KWIKI_MULTIMODAL_PROMPT_VERSION`（或模型名）
   会为新身份生成新摘要并复用已上传 contentId；要让存量索引吃到新摘要，
   需以新的 parser 代（例如 `kwiki-parse-2-p2`）登记清单并重建。

### 回滚

关闭 `KWIKI_MULTIMODAL_ENABLED` 并把别名切回旧版本即可；kwiki-parse-2
流水线随即不可解析（fail closed）。派生资产与摘要记录（
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
