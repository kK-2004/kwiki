# 浏览器直传

文档导入及编辑器的图片、音频、视频、普通附件均使用以下流程：

1. 浏览器先读取文件前 1 MiB，计算 `sha256-prefix-1m-v1` 下的前缀 SHA-256 及总大小。摘要计算只发生在浏览器。
2. 浏览器先发送摘要和元数据到 `POST /api/v1/knowledge-bases/{kbId}/attachments/dedup/prefix`，用 `(hashVersion, prefixSha256, byteSize)` 做候选初筛。
3. 前缀查询有候选时，浏览器再读取完整文件计算 `fullSha256`；无候选时也在初始化新上传前计算并保存完整哈希。随后浏览器发送完整摘要和元数据到 `POST /api/v1/knowledge-bases/{kbId}/attachments/uploads`，字段为 `fileName`、`contentType`、`byteSize`、`purpose`、`hashVersion`、`prefixSha256`、`fullSha256`。完整哈希命中已验证物理文件时返回 `REUSED`，直接创建当前用户自己的 attachment 引用；同一文件正在上传时返回 `PENDING`。
4. 需要上传时，浏览器对预签名 `putUrl` 发起 PUT，body 为 File，使用返回的 Content-Type，不携带 kwiki 的 Authorization、cookie 或 referrer。失败不会回退到 kwiki 中转。
5. 浏览器调用 `POST /api/v1/knowledge-bases/{kbId}/attachments/{attachmentUuid}/complete`。后端只接受自己保存的上传定位信息，校验上传者、知识库、状态、会话期限、远端实际大小及类型。重复确认不重复入队。
6. 媒体签名校验使用对象存储的 12 字节 Range GET；正常文档完成请求不下载文档。索引、文档解析仍需要读取原文件，这是处理阶段的下载流量。
7. 文档导入调用 `POST /api/v1/knowledge-bases/{kbId}/imports`，JSON 为 `attachmentUuid`、可选 `parentId`、`audienceMode`、`audienceMembers`，保留 `Idempotency-Key`。后台已有持久化 worker 读取、验证并解析文件，前端查询导入任务状态。

原来的 multipart 文档导入与附件上传路由已移除；前后端需要一起发布。内部生成的图片等仍可由服务器通过存储端口上传。

## 发布条件

- 执行 Flyway 新迁移 `V26__browser_upload_sessions.sql` 和 `V27__attachment_deduplication_hashes.sql`。`attachment_blob` 保存完整/前缀摘要和共享物理 fileId；会话只临时保存 provider locator 和可选初始化 fileId；完成后删除会话。
- 对象存储 CORS 必须允许实际前端 Origin、`PUT` 和 `Content-Type` 请求头。对象存储处理 OPTIONS 预检；不要把 kwiki token 配到存储端。HTTPS 页面必须获得 HTTPS 上传地址。
- 本轮没有修改线上桶策略，也没有连接真实内容中心/数据库做上传验收。上线前需用实际浏览器验证预检、PUT、确认、PDF 导入及编辑器媒体上传。
- 未完成、过期会话及云端孤立上传不复用；用户可重新选择文件上传。其清理应纳入后续对账，不能仅删除其他用户仍在引用的对象。旧测试附件不回填摘要，按约定删除后重新上传。
- 不保存预签名 URL。上传会话额外保留一小时完成确认宽限期；完成成功后重试使用已存储结果。

## 内容中心重复完成兼容

当前 SDK 0.1.3 对应内容中心在 complete 时消耗 UPLOADING 记录。对已经完成的请求重复调用，会返回 HTTP 400 `未找到上传初始化记录:`。适配器仅在这个明确响应、且会话有服务端初始化得到的 fileId 时进行恢复：申请该 fileId 的下载链接，以 `Range: bytes=0-0` 核对真实大小和 Content-Type。任何其他拒绝、无法校验的响应或大小/类型不一致均失败，不把浏览器声称的成功作为依据。

该兼容依赖当前内容中心错误契约；升级内容中心时应改为正式的幂等 complete 或文件状态查询接口。旧部署不返回初始化 fileId 时不执行此恢复，用户需要重新选择文件。Range 被忽略时也只读取所需前缀并关闭响应流，不缓冲完整文件。

## 验证

前端直传测试检查：文件只发给对象存储、认证信息不外泄、PUT 失败不确认、不回退到 kwiki、完成响应丢失时仅重试确认、取消时不发送请求。

后端测试覆盖：权限和知识库绑定、元数据与媒体签名校验、会话过期、重复完成、SDK 无代理 PUT、内容中心完成响应丢失恢复、导入请求仅排队且不下载或解析正文。
