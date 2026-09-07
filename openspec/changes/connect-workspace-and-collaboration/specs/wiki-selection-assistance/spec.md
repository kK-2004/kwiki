## ADDED Requirements

### Requirement: Validated revision-aware text selection anchors
Wiki 阅读页 SHALL 对单段非空划词提供评论/问 AI 入口，保存 page/revision/block/paragraphHash、UTF-16 start/end、quote 和前后文；服务端 SHALL 从授权修订读取正文验证锚点，跨段选择 SHALL 明确提示重新选择。

#### Scenario: Reader comments on a selection
- **WHEN** 用户划选有效段内文字并提交评论
- **THEN** 评论绑定可重新定位的真实版本锚点，不把客户端伪造段落视为 Wiki 正文

#### Scenario: Invalid anchor is submitted
- **WHEN** page、revision、段落或 offset/quote 不匹配，或用户没有读取该修订的权限
- **THEN** 拒绝提交，不调用 AI，不保存虚假的划词关系

### Requirement: Dedicated selection LLM client with server-built context
后端 SHALL 增加独立 SelectionQuestionLlmClient，按当前文档权限构造包含 Wiki 元信息、标题、修订、完整段落、划词及用户 query 的上下文。该 client SHALL 独立配置模型/预算/超时，支持流式答案、取消与错误；文档文本 SHALL 作为不可信数据传入。

#### Scenario: Reader asks about selected words
- **WHEN** 有权用户输入 query 请求划词解释
- **THEN** 独立 client 收到服务端读取的正确元信息、完整段落和划词，前端在划词问答区域显示流式回复

#### Scenario: Context is too large or permission is revoked
- **WHEN** 完整段落超出预算或发模型前权限失效
- **THEN** 返回明确错误或权限变化状态，不静默截断完整段落，不发送失权内容

### Requirement: Reliable anchor navigation across revisions
锚点跳转 SHALL 优先定位原修订/段落/offset；新修订只在 quote 和上下文唯一匹配时重定位，否则 SHALL 提示原文变化并提供仍可读的历史位置。

#### Scenario: Original text moves or disappears
- **WHEN** 消息跳转时文档已修订，划词可能移动或不存在
- **THEN** 唯一匹配时高亮并展开评论；无法唯一匹配时不错误高亮同名词，展示变化提示及授权历史入口
