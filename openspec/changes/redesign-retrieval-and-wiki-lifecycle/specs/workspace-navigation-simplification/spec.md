## ADDED Requirements

### Requirement: Workspace exposes only functioning navigation and chat window actions
系统 SHALL 删除共享空间和全局搜索导航/路由。聊天浮窗 SHALL 仅提供最小化和最大化动作。

#### Scenario: User opens global navigation
- **WHEN** 工作区导航渲染
- **THEN** 不显示共享空间和搜索入口，对应旧路由不再提供页面

#### Scenario: User opens floating chat
- **WHEN** 聊天浮窗展开
- **THEN** 标题栏只显示最小化与最大化按钮
