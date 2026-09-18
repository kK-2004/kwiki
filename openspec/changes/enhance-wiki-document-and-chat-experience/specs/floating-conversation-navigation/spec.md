## ADDED Requirements

### Requirement: Floating conversation exposes new and history actions
右下角会话浮窗展开时 SHALL 提供可识别的“新建会话”和“历史会话”按钮，且按钮可通过键盘操作并具有正确的可访问名称和状态。

#### Scenario: User creates a conversation from the floating window
- **WHEN** 用户点击浮窗中的“新建会话”
- **THEN** 系统通过全局 conversation store 清空当前选择并显示新会话输入态，不导航到完整聊天页

#### Scenario: User opens conversation history
- **WHEN** 用户点击浮窗中的“历史会话”
- **THEN** 浮窗组件内部显示本人的会话列表及加载、空、错误状态，浮窗保持展开

### Requirement: Historical conversations can be resumed in place
浮窗历史列表 SHALL 允许用户选择本人会话并在浮窗内加载授权后的消息和继续对话，选择行为 SHALL 复用完整聊天页的会话状态与接口。

#### Scenario: User selects a historical conversation
- **WHEN** 用户从浮窗历史列表选择一个可访问会话
- **THEN** 列表关闭、该会话历史显示在同一浮窗，后续消息追加到原 sessionId

#### Scenario: Historical evidence is no longer authorized
- **WHEN** 所选会话包含当前已失权的历史引用或证据
- **THEN** 浮窗应用与完整聊天页相同的遮蔽规则，不泄露失权内容

### Requirement: Floating navigation preserves single-run continuity
新建、查看历史、选择会话、最小化和展开完整页 MUST NOT 复制、重放或隐式取消正在生成的 turn；浮窗与完整页 SHALL 始终共用同一 store 和唯一流连接。

#### Scenario: User opens history during generation
- **WHEN** 当前浮窗会话仍在生成且用户打开历史列表或尝试选择另一会话
- **THEN** 当前 run 继续且只有一条流连接；若无法安全切换，系统明确阻止选择而不是取消或丢失 run

#### Scenario: User expands after selecting history
- **WHEN** 用户在浮窗选择历史会话后点击展开完整聊天
- **THEN** 完整页打开相同 sessionId、消息和运行状态，不重新获取或发送一个重复 turn

### Requirement: Floating history panel has contained navigation behavior
历史 panel SHALL 在浮窗边界内滚动和管理焦点；Escape SHALL 优先关闭 panel，只有 panel 已关闭时才执行浮窗自身的最小化行为。

#### Scenario: User navigates a long history list
- **WHEN** 会话数量超过浮窗可用高度或用户仅使用键盘
- **THEN** 列表在组件内部滚动、操作保持可达且焦点不会意外落到被遮挡页面
