# 设计：索引版本「存量迁移」与手动写入开关

日期：2026-09-30
范围：全局索引页（CHUNK 索引版本管理）与灰度发布自动流程

## 1. 背景与目标

现状是「重建 → 开始补齐」两段式：

- 重建（`ManualIndexRebuildService` → `FixedRangeRebuildScanner`）要求写入关闭，清空物理索引后按固定 ID 上界全量扫描；
- 开始补齐（`SwitchPreparationService.prepare`）会**自动**为所有合格版本开启写入（`enableForSwitchPreparation`），再由 `SwitchCatchupProcessor` 回放重建期间的事件与尾部范围；
- `SearchIndexSelectionRegistry` 选择版本时同样隐式开启写入。

问题：双写由系统隐式开启，管理员无法掌控；「待重建」状态下的灰度版本在全局页没有任何操作按钮；两个按钮的语义对管理员不直观。

目标：

1. 双写只由管理员（或灰度自动流程）通过显式的「写入」开关控制，删除所有隐式开启逻辑。
2. 用单一动作「开始存量迁移」替代「重建」+「开始补齐」：按版本配置的解析器从原始数据重建历史内容，截止到双写起点之前。
3. 灰度自动流程严格按「开启双写 → 存量迁移 → 校验」顺序推进。

## 2. 原始数据来源（已具备，无需新增存储）

- 页面：已发布修订 Markdown（`wiki_page.current_published_revision_id`）；
- PDF 导入页：原 PDF 作为附件存于内容中心，经 `SourceDocument(REL_DERIVED_FROM)` 关联；parse-2 通过 `IndexingWorker.importedPdfDocument` 读取原文件重新解析；
- 附件：内容中心原文件（`attachment.content_center_file_id`）。

迁移入队的仍是 PAGE / ATTACHMENT 的 UPSERT 目标，由 `IndexingWorker` 按目标版本的流水线解析，因此天然"按对应版本的解析器重新走一遍"。

## 3. 概念模型

### 3.1 写入开关

- 新增列 `search_index_version.write_enabled_event_id BIGINT NULL`（新 Flyway 版本 `V34__index_write_enabled_event.sql`，不改历史迁移文件）。
- **打开写入**（`enableWrites`）：
  1. 前置：未删除、流水线受支持、无活动迁移 run、当前写入关闭；
  2. 清空并重建该版本的空物理索引（`ElasticsearchIndexManager.recreateOfflineVersion`，按 mappingSchemaVersion 分支与现有重建一致）；
  3. 在锁定版本行的事务中：`writeEnabled=true`、`adminDisabled=false`、`catchupStatus=BEHIND`、`buildState=NEW`，记录 `write_enabled_event_id = MAX(search_index_change_event.id)`（即 E）。
  - 锁定版本行保证实时入队要么落在 E 之前（旧写集合，不含本版本），要么在提交后看到包含本版本的新写集合，边界事件不丢。
- **关闭写入**（`disableWrites`）：
  - 禁止：已发布（selected）版本、有活动迁移 run 的版本；
  - 效果：`writeEnabled=false`、`adminDisabled=true`、`catchupStatus=BEHIND`、`write_enabled_event_id=NULL`；
  - 再次打开会重新清空并需要全量迁移（关闭期间的删除/修改无法可靠补回，接受重做 embedding 的代价）。
- 全局页上灰度版本（`kbScoped`）的写入开关只读，由灰度流程控制。

### 3.2 存量迁移

- 前置：写入开启且 `write_enabled_event_id` 非空、状态为「待迁移」、流水线受支持、无活动 run、无引用该版本的活动图构建。**写入关闭时禁止迁移。**
- 新 `RebuildRunKind.MIGRATION`，复用 `VersionRebuildCoordinator` 的版本锁、全局并发槽、暂停/取消/进度记录。
- 启动时捕获各资源类型的 ID 上界（沿用 run range 机制），`FixedRangeRebuildScanner` 以迁移模式扫描：
  - **不**重建物理索引（已在打开写入时清空）；
  - 查询条件与现有扫描一致（含 `IndexVersionKbScope.sqlFilter` 灰度范围过滤、parse-2 纳入 PDF 附件），另加：
    `NOT EXISTS (SELECT 1 FROM search_index_change_event e WHERE e.resource_type=? AND e.resource_id=<id> AND e.id > E)`
    —— 截止到双写第一条数据之前；有更新事件的资源由双写负责。
  - 按批入队 `REBUILD:<runId>:...` 目标并等待全部完成；存在失败目标则 run 失败。
- 完成：`buildState=BUILT`、`builtConfigRevision=configRevision`、`catchupStatus=CURRENT`。
- 失败/取消：`buildState=FAILED`，写入保持开启，可再次发起迁移（截止点仍为原 E，已入索引的资源重复 UPSERT 为幂等覆盖）。

### 3.3 展示状态

`IndexDisplayStatus` 调整为（优先级从高到低）：

| 枚举 | 中文 | 条件 |
|---|---|---|
| `NEEDS_ATTENTION` | 需要处理 | `needsAttentionReason != null` |
| `MIGRATING` | 迁移中 | 有活动 MIGRATION run |
| `PUBLISHED` | 已发布 | `selected` |
| `PENDING_MIGRATION` | 待迁移 | dirty、非 BUILT，或 `catchupStatus=BEHIND` |
| `MIGRATED` | 已迁移 | 其余 |

移除 `CATCHING_UP`、`REBUILDING`、`PENDING_REBUILD`、`REBUILT`。

### 3.4 可执行操作（`allowedActions`）

| 动作 | 条件 |
|---|---|
| `edit` | 写入关闭、未选中、无活动 run（沿用 `snapshot.editable()`） |
| `writeToggle` | 非灰度；打开：写入关闭且无活动 run；关闭：写入开启、未选中、无活动 run |
| `migrate` | 非灰度、写入开启、状态待迁移、无活动 run |
| `migrateBlockedReason` | 待迁移但写入关闭时返回「请先开启写入」，前端展示为禁用按钮的提示 |
| `validate` | 沿用 |
| `select` | 非灰度、未选中、写入开启、已迁移（BUILT + 非 dirty + CURRENT）、多模态就绪 |
| `delete` | 沿用 |

`select` 的服务端守卫同步收紧；`SearchIndexSelectionRegistry` 不再调用 `enableForSwitchPreparation`，改为要求版本写入已开启。

## 4. 接口

`SearchIndexAdminController`：

- 新增 `PUT /versions/{n}/write`，body `{ "enabled": true|false }`，审计动作 `WRITE_ENABLE` / `WRITE_DISABLE`；
- 新增 `POST /versions/{n}/migrate`，返回 `{accepted, runId, code}`，审计动作 `MIGRATE`；
- 删除 `/versions/{n}/rebuild`、`/prepare`、`/disable`、`/reenable`。

均受 `kwiki.indexing.management.mutations-enabled` 与幂等键约束（沿用 `command(...)` 包装）。

## 5. 灰度自动流程

`GrayReleaseService`：

1. **开始同步 `sync`**：
   - 写入关闭 → 调用 `enableWrites(n)`（清空灰度索引、开启写入、记录 E；实时入队只对范围内知识库生效，沿用现有 kbScope 过滤）；
   - 写入已开启（失败重试）→ 保留双写与 E；
   - 清除 lastError，迁到 `SYNCING`。
2. **定时推进 `advance`**（`GrayReleaseSyncDriver` 分布式锁保留，防止多实例重复发起）：
   - 硬前置：写入开启且 E 非空，否则记录失败原因，不迁移；
   - 最新 run 不是本次写入会话（`write_enabled_event_id` 之后创建）的 MIGRATION run → 发起迁移；
   - run 活动中 → 等待；run 失败 → 记录 lastError，等待用户重试；
   - run 完成且版本 CURRENT → 校验，通过则 `SYNCED`，否则记录原因。
3. **结束灰度 `end`**：调用 `disableWrites`（替代 `enablement.disable`）；活动迁移中拒绝结束。

为判定"本次写入会话的迁移 run"，`search_index_rebuild_run` 复用已有 `build_start_event_id` 列记录发起时的 E，与版本当前 E 比对。

## 6. 删除的代码

- `SwitchPreparationService`、`SwitchPreparationBarrierService`、`SwitchCatchupProcessor`、`SwitchCatchupScheduler` 及其配置项（`indexing.catchup.*`）与测试；
- `IndexVersionEnablementService`（由新的 `IndexVersionWriteService` 替代）；
- `SearchIndexVersion.enableForSwitchPreparation`、`reenableByAdministrator`；
- `ManualIndexRebuildService`（由 `IndexMigrationService` 替代）；
- `IndexVersionStatusPolicy` 中 `startBuild/caughtUp/enableWrites/disableWrites` 等被替换的方法按新语义重写；
- 历史表列（`dual_write_start_event_id`、`switch_state`、`catchup_barrier_event_id` 等）不删除，只停止写入；`RunView` 保留字段以兼容历史记录展示。

实施时需 grep 确认上述类无其他引用（如图构建、校验服务对 `switchState=READY` 的依赖），有依赖则一并改为基于 MIGRATION run 完成判断。

## 7. 前端（admin-frontend）

- `IndexManagementView.vue` 版本表：状态列后新增「写入」列（`el-switch`）；
  - 打开前确认：「将清空该版本的物理索引并开始双写，之后需执行存量迁移」；
  - 关闭前确认：「关闭后该版本不再接收新内容，重新开启需重新全量迁移」；
  - 已发布、灰度、迁移中时开关禁用并带 tooltip 说明原因。
- 操作列：删除「重建」「开始补齐」「停用」「重新启用」；新增「开始存量迁移」（`migrate`），待迁移但写入关闭时显示禁用按钮 + 「请先开启写入」提示。
- `status.ts`：`PENDING_MIGRATION:"待迁移"`、`MIGRATING:"迁移中"`、`MIGRATED:"已迁移"`；`MIGRATING` 为 warning 色。
- Tab「重建与补齐」更名为「迁移记录」，说明文字更新；顶部统计卡「进行中任务」说明改为「正在迁移的 run」；解析器说明文案中的「已重建、补齐」改为「已迁移」。
- `api.ts` 类型同步新增字段与动作。
- 灰度页（`GrayReleaseView.vue` / `GrayReleaseCard.vue`）中涉及「重建 / 补齐」的文案改为「开启双写 / 存量迁移」。

## 8. 测试

- `IndexVersionStatusPolicyTest`：新展示状态推导；写入开关约束（已发布不可关、迁移中不可切）；完成迁移置 CURRENT。
- 新 `IndexVersionWriteServiceTest`：打开写入记录 E 并清空索引；关闭写入清空 E。
- 扫描器迁移模式测试：存在 `id > E` 变更事件的资源被跳过；灰度范围过滤仍生效；写入关闭时拒绝发起迁移。
- `GrayReleaseServiceTest`：断言顺序「开启写入 → 迁移 → 校验」；写入未开启时 advance 拒绝迁移；失败重试保留 E；结束灰度关闭写入。
- `SearchIndexSelectionRegistry` 相关测试：选择不再隐式开启写入，写入关闭时拒绝选择。
- 前端：`npm run build`（vue-tsc）通过。
