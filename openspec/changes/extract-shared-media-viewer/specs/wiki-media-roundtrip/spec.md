## ADDED Requirements

### Requirement: Complete media node replacement
媒体扫描器 SHALL 返回完整音视频节点边界，编辑和序列化 MUST 幂等，正确处理属性转义及支持的 source 子节点。

#### Scenario: Repeated video edits
- **WHEN** 视频插入后连续调整布局和大小五次，再保存并重开
- **THEN** 源码只存在一组对应视频标签，展示只有一个播放器且没有残留闭合标签文字

### Requirement: Literal code preservation and legacy compatibility
系统 SHALL 保留代码和转义文本，只修复确定属于媒体节点后紧邻的同名孤立闭合标签；阅读 MUST 不持久化修改历史内容。

#### Scenario: Legacy duplicated closing tags
- **WHEN** 普通正文包含完整 video 节点及紧随的重复 video 闭合标签
- **THEN** 编辑规范化只清理确定冗余的标签，明确保存后才持久化，其他文字不变

#### Scenario: Media syntax inside code
- **WHEN** 围栏、行内代码或转义文字包含 video 标签示例
- **THEN** 示例不被识别为可播放媒体或被清理

### Requirement: Consistent render and portable export
系统 SHALL 在源码卡片、预览与阅读页使用相同媒体展示行为，导出 SHALL 保留可移植媒体引用。

#### Scenario: Switch display contexts
- **WHEN** 用户切换源码、预览并打开已保存页面
- **THEN** 每个媒体只有一个展示实例，布局一致，切换时销毁旧播放器；静态导出不依赖 Vue 组件脚本
