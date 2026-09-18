# 搜索索引版本运维手册

## 发版与配置

应用发版只增加受支持的 parser、chunker、embedding 模型/维度或 mapping 能力，绝不自动创建、重建或切换索引。管理员在 `/admin/` 创建配置，版本号由服务端单调分配。`configRevision != builtConfigRevision` 表示“待重建”；基线完成后是“已重建”，此时还没有发起补齐。

生产包使用 `./mvnw -Padmin-ui package`，它按 `admin-frontend/pnpm-lock.yaml` 构建并把产物放入 jar 的 `static/admin/`。运行环境必须提供 MySQL、Elasticsearch、Redis/Redisson及所有启用写入版本所需的 embedding 凭据。

## 重建和发布

1. 在版本行点击“重建”。同一版本的首次及手动重建共用 `kwiki:lock:index-rebuild:vN`；BUSY 时沿用返回的 runId，不重复创建任务。
2. 观察固定 PAGE/ATTACHMENT ID 范围、游标和目标失败。暂停/恢复不会创建新 run；取消后才能新建 run。
3. 基线完成显示“已重建”。点击“开始补齐”后，所有未明确停用的受支持版本加入未来事件多写；目标同时执行范围尾扫和 change-event 重放，此阶段才显示“补齐中”。
4. 等范围、事件游标和实时任务屏障收敛，执行校验。校验以 MySQL 当前资源覆盖、生命周期、mapping/维度和探针查询为准，不要求不同 chunker 的 chunk 数相等。
5. 重新打开确认框并输入当前源索引到目标索引的精确转换。服务端重新校验，再用一次 `_aliases` remove/add 原子切换。只有 ES 实际别名目标显示“已发布”。

## 热切换、停用与清理

仍启用且持续追平的旧版本可按相同准备、校验和确认流程热切回。切换不会暂停内容事件，也不会自动停用原读版本。

停用只设置数据库 `adminDisabled=true`、`writeEnabled=false` 和 `catchupStatus=BEHIND`，ES 数据仍在。重新启用会先加入未来写入，并强制建立范围/事件补齐任务；通过校验前不能选择。

系统建议保留当前读版本与最近的另一个版本。更老版本只有在明确停用、非别名目标、无构建/补齐和活动目标任务时才是清理候选。清理没有定时任务，必须输入完整物理名并携带幂等键；成功的 ES acknowledgement、tombstone 和审计可在后台查看。

## 故障恢复

- Redis/Redisson 不可用：重建/切换锁失败关闭，不操作 ES；恢复 Redis 后重试同一管理动作。
- Worker 重启：使用原 run 的固定范围、事件游标、租约和 fencing token 继续，不创建重叠活动 run。
- 某版本双写失败：当前读版本的成功不被影子失败覆盖；修复模型/ES 后重试失败目标，重新补齐和校验。
- ES 已切换但数据库未提交：启动及定时对账以 ES 别名为真相修复 selected 状态，并记录 RECOVERED 审计。
- 新版本异常：若旧版本仍启用且 READY，按正常选择流程原子切回；若已停用，必须重新启用、补齐、校验，不能绕过门禁。

## 上线演练与证据

启用生产变更开关前，在预发布环境执行 `scripts/search-index-staging-drill.sh`。设置
`KWIKI_STAGING_BASE_URL`、`KWIKI_STAGING_ADMIN_TOKEN`、`KWIKI_STAGING_DRILL_DIR`，并按以下顺序操作：

1. 对一个已进入补齐阶段的 run 记录 `before` 快照，重启 worker，等待原 run 恢复后记录 `after-worker-restart`。
2. 暂时让一个影子版本的 ES/embedding 目标失败，产生一笔所有启用版本写入；确认在线版本成功、影子版本失败后记录 `after-partial-write`，随后恢复依赖。
3. 在隔离维护窗口模拟“ES 别名已提交、数据库事务未提交”，重启应用触发对账；出现 `RECONCILE/RECOVERED` 审计后记录 `after-reconcile`。
4. 正常准备、校验并选择新版本，记录 `after-switch`；再以同样门禁切回旧版本，记录 `after-switch-back`。
5. 设置 `KWIKI_STAGING_RUN_ID`、`KWIKI_STAGING_OLD_INDEX`、`KWIKI_STAGING_NEW_INDEX`，执行 `scripts/search-index-staging-drill.sh verify`。

校验器要求游标只前进不回退、部分多写的成功/失败按版本独立可见、ES 别名与数据库 selected 状态一致、对账有恢复审计，并验证切换与回切均为唯一别名目标。成功后生成 `verification.json`；演练必须连同全部阶段 JSON 保存到发布记录。任一断言失败都不得开启生产 mutation。

## 验证记录（2026-09-13）

- 后端：完整测试 548 项通过、0 失败；其中 33 项外部契约测试因未提供 `KWIKI_IT_*` 而按设计跳过。
- 管理端：`pnpm typecheck`、Vitest（3 个文件/3 项）和 Vite production build 均通过。
- 整包：`./mvnw -Padmin-ui -DskipTests package` 通过，jar 已核验包含 `/static/admin/index.html` 及 JS/CSS 产物。
- OpenSpec：`openspec validate manage-search-index-versions --strict` 与 `git diff --check` 通过。
- 已离线覆盖 ES mapping、容量门禁、索引创建/删除、别名原子切换与故障注入。真实 MySQL 迁移和真实 ES/Redis/内容中心/Qwen 联调不由仓库自行创建服务；需由运维提供 `.env.example` 中的连接配置，先运行 `scripts/verify-external-services.sh`，再以 `KWIKI_IT_MYSQL_URL`、`KWIKI_IT_MYSQL_USERNAME`、`KWIKI_IT_MYSQL_PASSWORD` 执行 `./mvnw -Pexternal-it verify`。
- 预发布中断演练还额外需要 `KWIKI_STAGING_BASE_URL`、`KWIKI_STAGING_ADMIN_TOKEN`，以及可重启 worker 的部署权限。缺少任一项时必须保持生产 mutation 关闭。
