## ADDED Requirements

### Requirement: Production workspace uses real business data
所有生产用户、知识库、Wiki、会话、最近访问、摘要、共享项目和统计 SHALL 从后端/MySQL 派生数据读取。前端 MUST NOT 以 mock、静态管理员或示例文档代替加载结果；测试 fixture SHALL 隔离于测试代码。

#### Scenario: Empty database or inaccessible backend
- **WHEN** 后端返回空列表或依赖不可用
- **THEN** 分别展示可操作空状态或错误/重试状态，不出现伪造文档、账号或统计

### Requirement: Complete navigable workspace surfaces
全局导航 SHALL 提供知识库、会话、智能体、共享空间、消息中心、用户主页/点赞收藏、真实最近访问和搜索页面。现有树/新建/摘要/历史/归档/索引控制 SHALL 对应实际操作和权限，不保留可点但无行为的按钮。

#### Scenario: User activates every global navigation entry
- **WHEN** 用户逐一点击新聊天、知识库、会话、智能体、共享空间、消息、账号、最近访问和搜索
- **THEN** 每个入口打开对应的实际页面/聊天输入，导航选中状态和 URL 一致，空集合仍有完整操作反馈

#### Scenario: User opens the agent and shared pages
- **WHEN** 用户访问智能体或共享空间
- **THEN** 分别看到数据库配置的真实 Agentic RAG 能力并可发起会话，或当前获邀资源/申请/邀请管理列表

#### Scenario: Reader activates a Wiki action
- **WHEN** 用户在权限允许下创建目录/页面、编辑发布、查看历史恢复、归档或查看索引任务
- **THEN** 调用相应 API 并刷新相关页面；摘要无数据时显示暂无摘要而非固定数字

### Requirement: Consistent route and API contracts
路由 SHALL 区分认证页与 AppShell 下各功能页，保留旧数字 Wiki URL 重定向。API client SHALL 解包 TransDTO、处理 HTTP 与业务错误、multipart、分页、取消和认证更新。

#### Scenario: API returns wrapped data or a business error
- **WHEN** HTTP 200 返回 TransDTO 成功数据或 success=false
- **THEN** 前端分别使用 data 或显示业务错误，不能把整个 envelope 当数组/正文

#### Scenario: Page changes during a slow load
- **WHEN** 用户从页面 A 快速切换到 B 后 A 请求才完成
- **THEN** B 保持选中且不被 A 结果覆盖；旧数字 URL 进入同一新 Wiki 页面

### Requirement: Accessible layouts and independent overflow
各页面与浮层 SHALL 支持键盘、焦点恢复、窄屏导航；聊天与成员选择窗口 SHALL 固定容器高度，固定标题/操作栏，溢出内容内部滚动。

#### Scenario: Long lists appear in a narrow viewport
- **WHEN** 成员树、消息或聊天内容超出容器
- **THEN** 内容可独立滚动且操作栏可用，窗口不被无限撑高，键盘可以关闭并返回触发入口
