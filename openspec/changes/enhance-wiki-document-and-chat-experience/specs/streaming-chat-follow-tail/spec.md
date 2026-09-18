## ADDED Requirements

### Requirement: Streaming regions follow the latest content while the user remains at the bottom
聊天消息区和展开的公开思考摘要区 SHALL 各自判断是否跟随底部；当用户未离开底部时，token、活动步骤、引用、历史加载和内容高度变化后 SHALL 自动显示各自最新内容。

#### Scenario: New streamed content arrives at the bottom
- **WHEN** 区域在底部阈值内且新 token、检索步骤、引用或思考摘要使内容增长
- **THEN** 系统在渲染后自动滚动该区域到底部，持续显示最新内容

#### Scenario: Conversation is initialized or switched
- **WHEN** 新会话、已保存会话或浮窗/完整页中的目标会话完成初始加载
- **THEN** 消息区从底部开始并进入自动跟随状态

### Requirement: User scrolling pauses and resumes follow-tail deterministically
每个可滚动区域 SHALL 独立维护用户跟随状态；用户主动上滚并离开底部阈值后，任何流式更新 MUST NOT 改变其 scrollTop，用户重新滚动到底部阈值内时 SHALL 自动恢复跟随。

#### Scenario: User reads earlier messages while generation continues
- **WHEN** 用户使用滚轮、触摸或键盘上滚消息区并离开底部，随后新内容到达
- **THEN** 消息区保持用户阅读位置且不被新内容拉回底部

#### Scenario: User returns to the bottom
- **WHEN** 已暂停跟随的用户主动滚动回底部阈值内
- **THEN** 区域立即恢复跟随，之后的新内容自动保持最新可见

#### Scenario: User scrolls inside expanded thinking content
- **WHEN** 用户在思考区上滚而外层消息区仍在底部，或反之
- **THEN** 只有被操作的区域暂停跟随，内外层滚动状态互不覆盖

### Requirement: Thinking expansion and collapse are animated without losing state
公开思考摘要的展开与折叠 SHALL 使用平滑的高度和透明度过渡，动画期间 MUST 保留已接收内容、滚动位置、跟随状态和正确的 `aria-expanded`/region 关系。

#### Scenario: Thinking content expands during streaming
- **WHEN** 用户在思考摘要持续增长时展开区域
- **THEN** 内容平滑展开；仅当思考区仍处于跟随状态时滚动到最新内容

#### Scenario: User collapses and reopens after reading earlier content
- **WHEN** 用户上滚暂停跟随后折叠并重新展开思考区
- **THEN** 过渡平滑且先前阅读位置与暂停跟随状态得到保留，不强制跳到底部

#### Scenario: Reduced motion is requested
- **WHEN** 操作系统设置 `prefers-reduced-motion: reduce`
- **THEN** 展开折叠立即完成或显著缩短动画，同时保持内容、滚动和可访问语义不变
