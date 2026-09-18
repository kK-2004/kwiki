## Why

kwiki 当前仅从 PDF 等文档中提取文本，并在 Markdown 页面索引时丢弃图片目标信息，导致 PDF 内嵌图片以及页面中手动上传或外链的图片在 ES 中都缺少关键语义与可回显资源。需要在现有 Java 索引流水线和 kFile 内容中心之上补齐发布后原生多模态解析能力，同时避免引入独立 MarkItDown/FastAPI 服务。

## What Changes

- 在 Java 文档解析流水线中识别 PDF 内嵌图片，保持图片与正文的文档顺序。
- 在 Markdown 页面发布后的索引任务中识别标准 Markdown 图片与 `<img>`：手动上传的 `attachment://uuid` 图片复用其现有内容中心 `contentId`，外部 HTTPS 图片经安全抓取和校验后镜像到内容中心。
- 将 PDF 提取图片和 Markdown 外链图片上传到现有 kFile 内容中心，以返回的 `contentId` 作为唯一持久资源标识，并通过内容中心 CDN 链接调用视觉模型与支持后续回显。
- 新增 OpenAI-compatible 视觉模型客户端，使用 `qwen3.7-flash` 和 `image_url` 多段消息为每张图片生成面向检索的摘要；不调用 MarkItDown 或任何外部文档解析服务。
- 保持页面源 Markdown 不变，仅在 ES 索引投影的图片原位置写入 `KWIKI_META_DATA` 受保护块；块元数据只包含 `type` 与 `contentId`，块正文包含视觉模型摘要，不持久化 CDN URL 或其他冗余字段。
- 使结构化分块、向量化与索引写入保留完整受保护块，避免标记或图片摘要被跨块截断；用于 embedding 的文本保留摘要语义。
- 定义图片提取、上传或视觉摘要失败时的错误分类、重试、幂等与清理行为，防止静默丢失图片语义或产生不可追踪的内容中心资源。
- 为检索结果中的多模态块提供结构化资源信息，使授权后的调用方可按 `contentId` 换取 CDN/预览链接并回显图片。

## Capabilities

### New Capabilities

- `multimodal-document-indexing`: Java 原生完成 PDF 内嵌图片及发布后 Markdown 上传/外链图片的解析、内容中心持久化、Qwen 视觉摘要、受保护块分片索引以及基于 `contentId` 的资源回显契约。

### Modified Capabilities

无。

## Impact

- 影响 PDF/Markdown 解析、页面发布后的异步索引任务、索引中间结果、父子分块、embedding 文本投影、Elasticsearch chunk 文档以及检索结果装配；不改变页面源 Markdown 和发布内容。
- 扩展现有内容中心存储用法：PDF 派生图片与 Markdown 外链镜像会成为独立内容对象；Markdown 手动上传图片复用已有内容对象，持久身份均为 `contentId`。
- 增加独立的视觉模型配置、客户端、超时/重试与可观测指标；凭据继续通过环境变量注入并执行日志脱敏。
- PDF 解析继续在 Java 进程内完成；不新增 Python、FastAPI、MarkItDown 或 OCR 服务依赖。
- 需要覆盖带图 PDF、纯文本 PDF、Markdown 上传图片、Markdown 外链图片、发布后异步生成、多图顺序、外链安全、失败重试、重复索引幂等、受保护块边界和资源回显的测试。
