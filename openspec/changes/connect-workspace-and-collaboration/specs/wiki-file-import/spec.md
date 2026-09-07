## ADDED Requirements

### Requirement: Authorized upload-to-Wiki form
知识库新建按钮 SHALL 打开包含文件、目标知识库/可选目录、标题、仅自己/指定知识库人员受众的上传模态框；服务端 SHALL 验证创建/上传权限、目标归属和全部受众。

#### Scenario: User imports a private document
- **WHEN** 有权限用户选择知识库、文件和仅自己并提交
- **THEN** 创建真实导入任务，使用所选归属和 PRIVATE 授权，页面显示上传/解析进度

### Requirement: Text-only DOCX and Markdown parsing
本导入入口 SHALL 仅支持通过大小、扩展名、内容签名/MIME 和文本校验的 DOCX/MD，沿用内容中心及文本 parser，将结构转为净化后的 Wiki Markdown，保留可表达的标题/段落/列表/表格/链接。

#### Scenario: Valid text document finishes parsing
- **WHEN** DOCX 或 Markdown 解析成功
- **THEN** 保存实际标题/正文、来源和受众，创建首个 Wiki 修订并发布，用户可以立即阅读和编辑

#### Scenario: Invalid or unsupported upload
- **WHEN** 文件伪装格式、超限、空文本或不属于 DOCX/MD
- **THEN** 返回明确失败，不能生成可见空白 Wiki；嵌入图片不执行多模态解析并给出提示

### Requirement: Idempotent and recoverable import lifecycle
导入 SHALL 持久化任务状态、幂等键、附件与页面关联，失败可查可重试，worker 恢复或重试 MUST NOT 重复创建页面。Wiki 生成与索引状态 SHALL 分离，Wiki 导入附件仅作为来源，不能重复索引同一内容。

#### Scenario: Submit or worker retries
- **WHEN** 用户重复提交同一幂等键或解析 worker 重试
- **THEN** 返回同一个导入结果/页面，权限在提交成功前重新复核，不出现多份 Wiki 或重复内容召回

#### Scenario: Indexing fails after Wiki creation
- **WHEN** 页面落库成功而 ES 索引失败
- **THEN** Wiki 仍可读取，UI 明确显示索引失败并可重试索引，不把解析重新执行为另一页面
