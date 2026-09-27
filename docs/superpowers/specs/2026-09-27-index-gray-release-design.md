# 索引灰度发布与管理端改版设计

日期：2026-09-27

## 1. 背景与目标

当前检索读别名 `kwiki-chunks` 全局唯一；解析器（`kwiki-parse-1` Tika / `kwiki-parse-2` PDFBox）是索引版本配置里的字段，导入 PDF 时读取「已发布版本」的解析器。因此只能全库一起切换，无法对部分知识库试用新解析器。

目标：

1. **按知识库灰度**：选若干知识库 + 一个解析器 → 自动建新索引版本 → 手动同步（存量源文件按新解析器重新解析入新索引）→ 手动切换读路由；可随时切回，旧版本保留、手动删除。
2. **全局设置独立**：全库默认索引、解析器与批量同步放在「全局索引」页，与灰度分开。
3. **管理端一眼可见**：全局页顶部直接展示「当前别名 → 物理索引」与「当前解析器」，点击即可切换。

非目标：解析器插件化注册（二期，见 §9）；解析器本身的实现（由解析器开发工作负责）。

## 2. 关键决策

| 决策 | 结论 |
|---|---|
| 路由机制 | MySQL 路由表 + 检索时按版本分组查询（方案 A），不使用每库 ES 别名 |
| 灰度索引范围 | 只包含灰度中的知识库 |
| 切回保鲜 | 灰度存续期间双写：灰度知识库的写入同时进入全局可写版本与灰度版本，各自使用各自的解析器 |
| 并行灰度 | 允许多个进行中的灰度；一个知识库同时只能属于一个进行中的灰度 |
| 灰度范围变更 | 开始同步后知识库列表锁定；需调整则结束当前灰度并新建 |
| 旧版本 | 永不自动删除；沿用现有「确认物理名」手动删除 |
| 解析器显示名 | `kwiki-parse-1` 显示为 `tika-v1`，`kwiki-parse-2` 显示为 `pdfbox-v2`；存储与接口仍用原标识 |

## 3. 数据模型（Flyway `V33__index_gray_release.sql`）

> `V32` 已被暂存的 MCP 改动占用，本迁移使用 `V33`；实现前再次核对 main 上的最新版本号。

`index_gray_release`

| 列 | 说明 |
|---|---|
| `id` | 主键 |
| `name` | 灰度名称（默认「{解析器显示名} 灰度 #{id}」，可编辑） |
| `parser_version` | 目标解析器，如 `kwiki-parse-2` |
| `index_version_number` | 自动创建的索引版本号（引用 `search_index_version.version_number`） |
| `status` | `CREATED` / `SYNCING` / `SYNCED` / `SWITCHED` / `ENDED` |
| `active_run_id` | 当前同步 run（可空） |
| `created_by`、`created_at`、`switched_at`、`ended_at` | 审计字段 |

`index_gray_release_kb`

| 列 | 说明 |
|---|---|
| `release_id`、`kb_id` | 联合主键 |
| `active_kb_id` | 灰度未结束时等于 `kb_id`，结束时置空；对其建唯一索引，保证一个知识库只在一个进行中的灰度里 |

「切回」不是独立状态：`SWITCHED` 表示灰度知识库读灰度版本；切回后状态回到 `SYNCED`（仍双写、可再次切换）。

## 4. 灰度生命周期

```
创建(CREATED) ──开始同步──▶ SYNCING ──同步+补齐+校验通过──▶ SYNCED ⇄ SWITCHED
     │                         │ 失败：停在 SYNCING，可重试         │
     └─────────────── 结束灰度（任一未结束状态）───────────────────▶ ENDED
```

1. **创建**：校验知识库均不在其他进行中的灰度；以当前全局已发布版本的配置为基础、替换 `parserVersion`，自动创建索引版本 vN（沿用现有建版本逻辑与物理名 `kwiki-chunks-v{n}`），标记其为灰度版本（`scope = 这些 kbId`）。**创建即开始对这些知识库双写**，保证同步期间新写入不丢。
2. **开始同步**：发起限定 `kbId IN (…)` 的重建 run（从源文件用新解析器重新解析），完成后自动补齐双写积压，再自动执行限定范围的校验。全部通过 → `SYNCED`。
3. **切换 / 切回**：仅 `SYNCED` 可切换到 vN（→ `SWITCHED`）；`SWITCHED` 可随时切回（→ `SYNCED`）。切换为单事务更新路由，立即生效。
4. **结束灰度**：知识库回到全局路由，停止向 vN 双写，释放 `active_kb_id`。vN 索引保留，状态显示为「可清理」，由管理员手动删除。

## 5. 后端链路

### 5.1 读路由 `KnowledgeBaseIndexRouter`

- 输入：本次检索涉及的 `kbId` 集合。
- 输出：`List<RouteGroup(physicalIndexOrAlias, kbIds)>`。
  - 属于 `SWITCHED` 灰度的知识库 → 该灰度版本的物理索引。
  - 其余 → 全局别名 `kwiki-chunks`。
- 调用方：`VectorRecallAdapter`、`Bm25RecallAdapter`、`EsChunkLookup`、`EsGraphSourceChildResolver` 等所有读 `ElasticsearchIndexManager.ALIAS` 的位置，改为按分组查询（ES 多索引查询，每组附加 `kbId` terms 过滤），结果合并后沿用原排序/融合逻辑。
- 路由快照按请求读取一次，同一次检索内一致。

### 5.2 写目标

`IndexWriteTargets.current()` 增加按知识库的重载：目标 = 全局可写版本 ∪ 该知识库所在未结束灰度的版本。每个目标版本使用其配置中的解析器处理（`VersionedIndexingPipelineRegistry` 已按版本配置选择流水线）。

### 5.3 限定范围重建

`ManualIndexRebuildService` / `FixedRangeRebuildScanner` 支持可选的 `kbIds` 范围；run 记录范围，统计按范围计算。

### 5.4 导入解析

`WikiImportDocumentParser` 取解析器时：知识库属于 `SWITCHED` 灰度 → 灰度解析器；否则 → 全局已发布版本的解析器（现有逻辑）。

### 5.5 接口（`/api/v1/admin/search-indexes/gray-releases`）

| 方法 | 路径 | 作用 |
|---|---|---|
| GET | `/gray-releases` | 列表（含状态、知识库、同步统计、可执行操作） |
| POST | `/gray-releases` | 创建（`{name?, parserVersion, kbIds}`） |
| POST | `/gray-releases/{id}/sync` | 开始/重试同步 |
| POST | `/gray-releases/{id}/switch` | 切换到灰度版本 |
| POST | `/gray-releases/{id}/switch-back` | 切回全局版本 |
| POST | `/gray-releases/{id}/end` | 结束灰度 |
| GET | `/parsers` | 可用解析器列表（标识、显示名、是否可用及原因） |

全部写操作沿用 `Idempotency-Key` 与审计（`SearchIndexAudit`）。服务端根据状态计算 `allowedActions`，前端只按其显示按钮。

## 6. 全局切换（管理端需求）

- 全局索引页顶部两张卡片：
  - **当前别名**：`kwiki-chunks → kwiki-chunks-v1`，副文「线上读请求实际命中的物理索引」。
  - **当前解析器**：`tika-v1`（`kwiki-parse-1`）。
- 点击任一卡片弹出选择：列出可发布的全局版本（`allowedActions.select`），每项显示物理名与解析器；选中后二次确认，调用现有 `POST /versions/{n}/select`。两张卡片调用同一动作（解析器随版本切换），弹窗中明确说明这一点。
- 无可发布版本时，给出「新建版本并同步」入口（现有流程）。

## 7. 管理端界面

- 侧边栏：「全局索引」「灰度发布」「知识图谱」。
- **全局索引页**：§6 的两张卡片 → 其余指标 → 现有版本列表与重建/补齐/校验/审计页签（整理层级，减少首屏文字）。
- **灰度发布页**：
  - 顶部「新建灰度」按钮；弹窗内：解析器单选（不可用的置灰并注明原因）、知识库可搜索多选（已在其他灰度的置灰并标注所在灰度）、名称（可选）。
  - 灰度卡片：名称、解析器、状态徽标、知识库标签、同步进度（已同步/总数、失败数、双写积压）、操作按钮（开始同步 / 切换 / 切回 / 结束）。
  - 进行中的 run 轮询刷新（沿用现有轮询与退避）。
  - 危险操作（切换、切回、结束）二次确认，文案说明影响范围。

## 8. 错误处理

- 创建时知识库冲突 → 409，返回冲突的知识库与所在灰度。
- 同步失败 → 停在 `SYNCING`，展示失败原因，可重试；不影响线上读取。
- 切换前再次校验状态与校验报告，不满足 → 409。
- 解析器不可用（如 `kwiki-parse-2` 缺少配置，沿用 `MultimodalSwitchReadiness`）→ 创建与切换均拒绝并给出缺失项。
- 删除灰度版本索引前，要求灰度已结束。

## 9. 二期（不在本次范围）

- 解析器注册表：新增解析器时声明标识、显示名、可用性检查，灰度页自动出现。
- 灰度转全量的一键推进。

## 10. 测试

- 后端：
  - 路由解析（混合灰度/非灰度知识库分组正确；切换后立即生效）。
  - 写目标（灰度存续期间双写，结束后停止）。
  - 状态机（非法转换拒绝；并发创建时知识库唯一性）。
  - 限定范围重建（只处理范围内知识库）。
  - 导入解析器选择。
- 管理端：灰度卡片按 `allowedActions` 渲染按钮；新建弹窗的搜索与冲突置灰；全局卡片切换弹窗。

## 11. 实施前置条件

另一会话正在修改 `kwiki-parse-2`、`MultimodalSwitchReadiness`、`AliasSwitchService`、`VersionedIndexingPipelineRegistry` 及 `IndexManagementView.vue`（未提交）。**需在其提交后再开始实现**，以免冲突。
