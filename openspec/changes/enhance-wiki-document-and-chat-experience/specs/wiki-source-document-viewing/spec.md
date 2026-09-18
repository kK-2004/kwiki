## ADDED Requirements

### Requirement: Imported PDF and DOCX pages expose source and parsed-text tabs
由有效 PDF 或 DOCX 来源导入的 Wiki 页面 SHALL 显示“<格式> 源文件”和“解析文本”两个页签；解析文本 SHALL 继续使用现有 Wiki 阅读视图，源文件 SHALL 在页面内使用在线预览组件显示对应原文件。

#### Scenario: User opens an imported PDF page
- **WHEN** 有权用户打开来源为 PDF 的 Wiki 页面并选择“PDF 源文件”
- **THEN** 系统在页面内直接预览该原始 PDF，用户可切回“解析文本”查看现有渲染正文

#### Scenario: User opens an imported DOCX page
- **WHEN** 有权用户打开来源为 DOCX 的 Wiki 页面并选择“DOCX 源文件”
- **THEN** 系统在页面内预览该原始 DOCX，用户可切回“解析文本”且正文阅读状态不被不必要地重建

#### Scenario: User opens Markdown or ordinary Wiki content
- **WHEN** 页面来源为 Markdown、没有来源附件或不是受支持的 PDF/DOCX
- **THEN** 系统不显示源文件/解析文本页签，继续显示现有 Wiki 阅读视图

### Requirement: Source preview preserves document authorization
来源元数据和文件内容 SHALL 通过页面作用域的授权检查获取；系统 MUST 验证附件确实属于目标页面，MUST NOT 向未授权用户或持久化前端状态暴露永久公开文件地址。

#### Scenario: Authorized source preview request
- **WHEN** 仍有页面读取权限的用户请求该页关联来源文件
- **THEN** 服务端重新校验页面、来源附件关联和文件状态，再返回受保护内容或短生命周期预览地址

#### Scenario: Unauthorized or mismatched source request
- **WHEN** 用户失去页面权限、伪造其他页面附件身份或来源附件已失效
- **THEN** 服务端拒绝请求且响应不泄露文件名、地址或内容，已打开的预览进入不可访问状态

### Requirement: Every source preview displays a user-identifying watermark
PDF/DOCX 源文件预览 SHALL 在全部可滚动可视区域持续显示不可交互的重复水印，至少包含当前用户可识别身份和查看时间；加载、翻页、缩放及窗口尺寸变化 MUST NOT 移除水印。

#### Scenario: Preview content loads and scrolls
- **WHEN** 用户加载、缩放、翻页或滚动源文件预览
- **THEN** 水印持续覆盖可视区域但不阻止预览控件和文本阅读

#### Scenario: Preview cannot load
- **WHEN** 文件过大、格式损坏、网络失败或预览组件不支持该内容
- **THEN** 系统显示明确失败原因和重试入口，不回退为无水印的公开嵌入

### Requirement: Preview resources are bounded and cleaned up
预览组件 SHALL 按需加载并限制资源消耗，切换页面、来源页签或卸载组件时 MUST 取消未完成渲染并释放临时 URL 和观察器。

#### Scenario: User rapidly switches pages or tabs
- **WHEN** 原文件仍在加载或渲染时用户切换到解析文本或另一页面
- **THEN** 旧任务被取消且不能覆盖新页面状态，相关 Blob URL、worker 和监听器被释放
