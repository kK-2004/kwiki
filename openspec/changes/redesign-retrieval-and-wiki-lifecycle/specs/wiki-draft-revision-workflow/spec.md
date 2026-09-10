## ADDED Requirements

### Requirement: Draft, publication and revision history form a complete workflow
系统 SHALL 将未发布草稿保存为独立、可覆盖的工作副本，再次编辑时恢复该草稿；保存草稿不得创建编号版本，阅读与检索只使用已发布修订。发布 MUST 先收集发布说明；留空时模型依据首次内容或相对上一已发布版本的差异生成，且每次发布只创建一个不可变版本。查看旧版本 MUST 为只读操作，只有显式「恢复到当前版本」才创建新的当前已发布版本。

#### Scenario: User returns after saving a draft
- **WHEN** 用户保存草稿、离开页面后再次编辑
- **THEN** 编辑器加载该草稿，阅读页仍显示上一已发布版本

#### Scenario: Draft is saved repeatedly
- **WHEN** 用户在一次发布前多次保存草稿
- **THEN** 系统覆盖同一工作副本且版本历史不增加

#### Scenario: Publication note is blank
- **WHEN** 用户在发布窗口留空说明并确认
- **THEN** 模型生成简短说明，说明随发布修订保存

#### Scenario: Old revision is inspected
- **WHEN** 用户点击历史旧版本
- **THEN** 弹窗显示其内容且不创建版本；仅显式恢复才生成新的当前已发布版本
