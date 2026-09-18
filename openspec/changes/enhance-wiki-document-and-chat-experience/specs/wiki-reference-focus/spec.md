## ADDED Requirements

### Requirement: Reference navigation visibly focuses the resolved source
系统 SHALL 在用户点击回答正文中的引用标记或参考列表项后，打开有权访问的对应 Wiki 页面与修订，并在正文渲染完成后将实际引用范围滚动到可见区域和临时高亮。

#### Scenario: Reference opens another page
- **WHEN** 用户点击指向另一 Wiki 页面的有效参考项
- **THEN** 系统加载目标页、解析 child chunk，在正文就绪后将命中范围滚动到视口中部并显示可辨识的临时高亮

#### Scenario: Current-page reference is selected
- **WHEN** 用户点击当前页面中的有效参考项
- **THEN** 系统不重复加载页面或丢失阅读状态，并对对应范围执行同样的滚动与高亮

### Requirement: Focus resolution is deterministic and lifecycle-safe
引用定位 SHALL 使用稳定 chunk 身份、页面/修订身份和可验证文本范围；异步定位任务 MUST 在目标改变或组件卸载时失效，高亮 MUST 在超时、再次定位或离开页面时清理。

#### Scenario: Page rendering is asynchronous
- **WHEN** 引用解析先于 Markdown 和媒体内容完成渲染
- **THEN** 系统等待当前目标正文提交后再定位，且旧页面的迟到任务不能高亮新页面内容

#### Scenario: Browser lacks Custom Highlight API
- **WHEN** 浏览器不支持 CSS Custom Highlight API
- **THEN** 系统使用不破坏正文结构的临时范围标记提供等价可见高亮并按生命周期清理

### Requirement: Unresolvable references fail without false highlighting
当引用失权、已删除、修订变化或文本匹配不唯一时，系统 MUST NOT 猜测并高亮相似文本，且 SHALL 向用户显示可理解的不可定位状态和可用的命中摘要。

#### Scenario: Referenced text changed or is ambiguous
- **WHEN** excerpt 无法在目标修订中唯一或按字符位置可靠匹配
- **THEN** 页面保持可读、没有错误高亮，并提示内容可能已更新且展示可用引用摘要

#### Scenario: Reference is no longer authorized
- **WHEN** 当前用户在跳转或解析时已失去引用页面权限
- **THEN** 系统不泄露页面、标题或片段内容，并显示引用不可访问状态
