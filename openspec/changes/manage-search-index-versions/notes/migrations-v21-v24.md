# 迁移与回滚兼容说明（任务 2.6）

## 新增迁移

- `V21__search_index_versions.sql` — `search_index_version`（+ `selected_key`
  生成列唯一约束保证至多一个 selected；`write_enabled`/`deleted_at` 复合索引）。
- `V22__search_index_rebuild_runs.sql` — `search_index_rebuild_run`
  （`active_version` 生成列唯一约束保证每版本至多一个活动 run）+
  `search_index_rebuild_range`（每资源类型固定区间与游标，
  `UNIQUE(run_id, resource_type)`）。
- `V23__search_index_change_events.sql` — 追加式 `search_index_change_event`
  （单调自增 id 即水位）+ `indexing_job_target`（幂等键含事件、目标版本与操作；
  认领索引 `(target_version, state, next_attempt_at)`）。
- `V24__search_index_audit.sql` — `search_index_audit`（含 PENDING 中间态）+
  `search_index_idempotency`（管理请求幂等重放）。

## 验证状态

- 离线：`scripts/validate-migrations.sh` 通过（24 个迁移，版本唯一、命名合法）。
- 数据库：`SearchIndexMigrationContractTest`（门控 `KWIKI_IT_MYSQL_URL`）
  执行 Flyway clean→migrate 全量迁移，并断言：版本号/物理名唯一、
  selected 唯一、`built_config_revision <= config_revision` CHECK、
  每版本活动 run 唯一且终态释放资格。本机未配置 MySQL IT 凭据，
  首次具备环境后必须执行（任务 12.4 汇总）。
- 从 V20 增量执行：V21–V24 仅新增表，不修改既有表，Flyway 顺序应用即可。

## 回滚兼容

- 四个迁移只新增表与索引：回滚应用二进制到旧版本时，旧代码不认识新表，
  直接照常运行（读路径始终是 `kwiki-chunks` 别名）。
- **唯一的前置条件**：显式目标写入（phase 4/5）上线后，若要把应用回滚到
  不认识 `indexing_job_target` 的旧版本，必须先把写目标兼容模式恢复为
  单一当前版本（管理端停用除当前版本外的全部写目标并等待队列清空），
  否则旧 worker 无法消费扇出目标行。
- 不提供 down 迁移；需要彻底清理时按 V24→V21 逆序 DROP（保留
  `flyway_schema_history` 中删除记录，或使用 Flyway clean + 重放到目标版本，
  仅限空数据环境）。
