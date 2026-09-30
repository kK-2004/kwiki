# 存量迁移与手动写入开关 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用「写入开关 + 开始存量迁移」替代「重建 + 开始补齐」，删除所有隐式开启双写的逻辑，灰度自动流程改为「开启双写 → 存量迁移 → 校验」。

**Architecture:** 每个索引版本新增「写入会话起点」`write_enabled_event_id`（E）。开启写入 = 清空物理索引 + 加入写目标 + 记录 E；存量迁移 = 新的 `MIGRATION` run，复用现有重建协调器与固定范围扫描器，只入队「E 之后没有变更事件」的资源，其余由双写负责。校验改为基于「本写入会话的已完成迁移 run + 双写积压清零」判断；旧的切换准备 / 补齐 / 屏障代码整体删除。

**Tech Stack:** Java 21 + Spring Boot + Spring Data JPA + JdbcTemplate + Flyway(MySQL) + Elasticsearch；前端 Vue 3 + TypeScript + Element Plus（`admin-frontend`）；测试 JUnit 5 + Mockito + AssertJ。

**Spec:** `docs/superpowers/specs/2026-09-30-index-migration-write-toggle-design.md`

## Global Constraints

- 所有新增/修改的注释使用简体中文（AGENT.md §1）；标识符、SQL、报错原文保持英文。
- `src/main/resources/db/migration/` 下已有文件**只读**，只能新增 `V34__...`（AGENT.md §2）；新增后运行 `scripts/validate-migrations.sh`。
- 不引入 Docker / Testcontainers（AGENT.md §3）；测试一律用 Mockito 或已有测试设施。
- 不写入任何真实密钥（AGENT.md §4）。
- 提交信息用中文 conventional 风格（如 `feat(indexing): ...`），**不要添加 `Co-Authored-By` 行**。
- 运行单测：`./mvnw -q test -Dtest=<类名>`；全量：`./mvnw -q test`；前端：`cd admin-frontend && npm run build`。
- 与 spec 的一处有意偏差：`IndexingProperties.Catchup` 配置记录**保留不删**（删除会波及大量测试构造且无收益），仅不再被使用。
- 数据库历史列（`dual_write_start_event_id`、`switch_state`、`catchup_barrier_event_id`、`tail_*`）不删除，只停止写入。

## File Structure

| 文件 | 动作 | 职责 |
|---|---|---|
| `src/main/resources/db/migration/V34__index_write_session.sql` | 新建 | 新列 `write_enabled_event_id` 与回填 |
| `indexing/version/SearchIndexVersion.java` | 改 | 写入会话字段与 `startWriteSession/endWriteSession` |
| `indexing/version/IndexVersionWriteService.java` | 新建 | 写入开关（开启清空索引+记录 E；关闭） |
| `indexing/version/RebuildRunKind.java` | 改 | 新增 `MIGRATION` |
| `indexing/version/IndexVersionStatusPolicy.java` | 改 | 迁移状态迁移函数、新展示状态 |
| `indexing/version/IndexDisplayStatus.java` | 改 | `PENDING_MIGRATION/MIGRATING/MIGRATED` |
| `indexing/version/JpaRebuildRunRegistry.java` | 改 | MIGRATION run 的创建/完成 |
| `indexing/version/FixedRangeRebuildScanner.java` | 改 | 迁移模式截止过滤 |
| `indexing/version/VersionRebuildCoordinator.java` | 改 | `startMigration` 入口 |
| `indexing/version/IndexMigrationService.java` | 新建 | 存量迁移资格预检与发起 |
| `indexing/version/SearchIndexValidationService.java` | 改 | 同步判断改为基于迁移 run |
| `indexing/version/SearchIndexSelectionRegistry.java` | 改 | 选择不再隐式开启写入 |
| `indexing/gray/GrayReleaseService.java` / `GrayReleaseSyncDriver.java` | 改 | 灰度按「开写入→迁移→校验」推进 |
| `indexing/version/SearchIndexAdminQueryService.java` | 改 | 新动作集合、`migrateBlockedReason` |
| `wiki/api/SearchIndexAdminController.java` | 改 | `PUT /write`、`POST /migrate`，删旧接口 |
| `indexing/version/SearchIndexDeletionService.java` | 改 | 删除前置改为「写入关闭」 |
| `SwitchPreparationService`、`SwitchPreparationBarrierService`、`SwitchCatchupProcessor`、`SwitchCatchupScheduler`、`IndexVersionEnablementService`、`ManualIndexRebuildService` | 删 | 旧两段式流程 |
| `admin-frontend/src/{api.ts,status.ts,views/IndexManagementView.vue,views/GrayReleaseView.vue,components/GrayReleaseCard.vue,components/CurrentIndexCards.vue}` | 改 | 写入列、迁移按钮与文案 |

（Java 路径均位于 `src/main/java/com/kwiki/` 下，测试位于 `src/test/java/com/kwiki/` 对应包。）

---

### Task 1: 写入会话列与写入开关服务

**Files:**
- Create: `src/main/resources/db/migration/V34__index_write_session.sql`
- Modify: `src/main/java/com/kwiki/indexing/version/SearchIndexVersion.java`
- Create: `src/main/java/com/kwiki/indexing/version/IndexVersionWriteService.java`
- Test: `src/test/java/com/kwiki/indexing/version/IndexVersionWriteServiceTest.java`

**Interfaces:**
- Produces:
  - `SearchIndexVersion#getWriteEnabledEventId(): Long`
  - `SearchIndexVersion#startWriteSession(long eventId): void`（包内可见）
  - `SearchIndexVersion#endWriteSession(): void`（包内可见）
  - `IndexVersionWriteService#enable(int versionNumber): SearchIndexVersion`
  - `IndexVersionWriteService#disable(int versionNumber): SearchIndexVersion`

- [ ] **Step 1: 确认变更事件表的列名与索引**

打开定义 `search_index_change_event` 的迁移文件（用 `grep -l "CREATE TABLE search_index_change_event" src/main/resources/db/migration/*.sql` 定位），确认列名为 `resource_type`、`resource_id`，并记录是否已有以 `(resource_type, resource_id)` 开头的索引。后续 Task 2 的截止过滤依赖这两列；若列名不同，Task 2 的 SQL 以实际列名为准。

- [ ] **Step 2: 新建迁移文件**

`src/main/resources/db/migration/V34__index_write_session.sql`：

```sql
-- 写入会话起点：版本开启写入（双写）那一刻的变更事件水位 E。
-- 存量迁移只覆盖 E 之后没有再发生变更的资源，其余由双写负责。
ALTER TABLE search_index_version
    ADD COLUMN write_enabled_event_id BIGINT NULL AFTER write_enabled;

-- 已处于写入状态的存量版本以当前事件水位作为会话起点
UPDATE search_index_version
SET write_enabled_event_id = (SELECT COALESCE(MAX(id), 0) FROM search_index_change_event)
WHERE write_enabled = TRUE AND deleted_at IS NULL;
```

若 Step 1 发现**没有** `(resource_type, resource_id)` 前缀索引，在文件末尾追加：

```sql
-- 存量迁移按资源查询 E 之后的变更事件
CREATE INDEX idx_change_event_resource ON search_index_change_event (resource_type, resource_id, id);
```

- [ ] **Step 3: 运行迁移校验脚本**

Run: `scripts/validate-migrations.sh`
Expected: 退出码 0，无报错。

- [ ] **Step 4: 写失败测试**

`src/test/java/com/kwiki/indexing/version/IndexVersionWriteServiceTest.java`：

```java
package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IndexVersionWriteServiceTest {

    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
    private final ElasticsearchIndexManager indexes = mock(ElasticsearchIndexManager.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    private final EditableIndexConfig config =
            new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 3);
    private final String hash = new ChunkMappingBuilder().mappingHash(1024, 3);
    private IndexVersionWriteService service;

    static IndexingProperties properties(boolean mutations) {
        return new IndexingProperties(List.of(), Map.of(),
                new IndexingProperties.Rebuild(50, 2, 20),
                new IndexingProperties.Catchup(100, Duration.ofSeconds(30)),
                new IndexingProperties.Capacity(20, 8, Duration.ofSeconds(5)),
                new IndexingProperties.Management(mutations));
    }

    @BeforeEach
    void setUp() {
        service = new IndexVersionWriteService(versions, runs, indexes, jdbc, tx, properties(true));
    }

    private SearchIndexVersion offline() {
        SearchIndexVersion version = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(version));
        when(versions.findByVersionNumberForUpdate(2)).thenReturn(Optional.of(version));
        return version;
    }

    @Test
    void 开启写入_先清空物理索引再记录双写起点() {
        SearchIndexVersion version = offline();
        when(jdbc.queryForObject(contains("indexing_job_target"), eq(Long.class), anyInt())).thenReturn(0L);
        when(jdbc.queryForObject(contains("search_index_change_event"), eq(Long.class))).thenReturn(42L);

        SearchIndexVersion enabled = service.enable(2);

        InOrder order = inOrder(indexes, jdbc);
        order.verify(indexes).recreateOfflineVersion("kwiki-chunks-v2", 1024, 3);
        order.verify(jdbc).queryForObject(contains("search_index_change_event"), eq(Long.class));
        assertThat(enabled.isWriteEnabled()).isTrue();
        assertThat(enabled.getWriteEnabledEventId()).isEqualTo(42L);
        assertThat(enabled.getBuildState()).isEqualTo(IndexBuildState.NEW.name());
        assertThat(enabled.getCatchupStatus()).isEqualTo(IndexCatchupStatus.BEHIND.name());
    }

    @Test
    void 仍有未完成写入任务时拒绝开启且不清空索引() {
        offline();
        when(jdbc.queryForObject(contains("indexing_job_target"), eq(Long.class), anyInt())).thenReturn(3L);
        assertThatThrownBy(() -> service.enable(2)).isInstanceOf(IllegalStateException.class);
        verify(indexes, never()).recreateOfflineVersion(anyString(), anyInt(), anyInt());
    }

    @Test
    void 迁移运行中拒绝开启() {
        offline();
        when(runs.existsByVersionNumberAndStateIn(eq(2), anyCollection())).thenReturn(true);
        assertThatThrownBy(() -> service.enable(2)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 关闭写入_清空双写起点并标记落后() {
        SearchIndexVersion version = offline();
        version.startWriteSession(42L);
        SearchIndexVersion disabled = service.disable(2);
        assertThat(disabled.isWriteEnabled()).isFalse();
        assertThat(disabled.getWriteEnabledEventId()).isNull();
        assertThat(disabled.getCatchupStatus()).isEqualTo(IndexCatchupStatus.BEHIND.name());
    }

    @Test
    void 已发布版本拒绝关闭写入() {
        SearchIndexVersion published = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config, hash);
        when(versions.findByVersionNumberForUpdate(1)).thenReturn(Optional.of(published));
        assertThatThrownBy(() -> service.disable(1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 管理写操作关闭时拒绝() {
        service = new IndexVersionWriteService(versions, runs, indexes, jdbc, tx, properties(false));
        assertThatThrownBy(() -> service.enable(2))
                .hasMessage(SearchIndexAdminService.MUTATIONS_DISABLED_MESSAGE);
    }
}
```

- [ ] **Step 5: 运行测试确认失败**

Run: `./mvnw -q test -Dtest=IndexVersionWriteServiceTest`
Expected: 编译失败（`IndexVersionWriteService`、`startWriteSession` 不存在）。

- [ ] **Step 6: 修改实体**

`SearchIndexVersion.java`：在 `private boolean writeEnabled;` 下一行加字段：

```java
    /** 写入会话起点 E：开启写入时的变更事件水位；写入关闭时为 null。 */
    private Long writeEnabledEventId;
```

在 `bootstrapped(...)` 中 `entity.writeEnabled = true;` 之后加：

```java
        entity.writeEnabledEventId = 0L;
```

在 `isWriteEnabled()` 之后加 getter：

```java
    public Long getWriteEnabledEventId() {
        return writeEnabledEventId;
    }
```

在 `tombstone()` 方法体末尾加 `this.writeEnabledEventId = null;`。

在 `enableForSwitchPreparation()` 之前新增两个方法（旧方法在 Task 6 删除）：

```java
    /**
     * 开启写入会话：物理索引已被清空，从事件水位 eventId 之后的变更开始双写；
     * 历史数据需由随后的存量迁移补入，因此构建状态回到 NEW。
     */
    void startWriteSession(long eventId) {
        if (deletedAt != null) {
            throw new IllegalStateException("deleted version cannot enable writes");
        }
        if (!pipelineSupported) {
            throw new IllegalStateException("unsupported version cannot enable writes");
        }
        if (writeEnabled) {
            throw new IllegalStateException("version " + versionNumber + " already accepts writes");
        }
        writeEnabled = true;
        adminDisabled = false;
        writeEnabledEventId = eventId;
        buildState = IndexBuildState.NEW.name();
        builtConfigRevision = null;
        catchupStatus = IndexCatchupStatus.BEHIND.name();
    }

    /** 结束写入会话：退出写目标集合；再次开启需清空并重新全量迁移。 */
    void endWriteSession() {
        if (selected) {
            throw new IllegalStateException("selected version cannot disable writes");
        }
        writeEnabled = false;
        adminDisabled = true;
        writeEnabledEventId = null;
        catchupStatus = IndexCatchupStatus.BEHIND.name();
    }
```

- [ ] **Step 7: 新建写入开关服务**

`src/main/java/com/kwiki/indexing/version/IndexVersionWriteService.java`：

```java
package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 管理员显式控制版本是否承接实时索引写入（双写）。系统中不再存在任何隐式开启写入的路径。
 * 开启时先清空物理索引，再在锁定版本行的事务中加入写目标集合并记录事件水位 E：
 * 实时入队读取写目标时与该行锁互斥，因此 E 之前的事件不会写入本版本，E 之后的事件一定会。
 */
@Service
public class IndexVersionWriteService {

    private static final List<String> ACTIVE_RUN_STATES = List.of(
            RebuildRunState.PENDING.name(), RebuildRunState.RUNNING.name(),
            RebuildRunState.PAUSED.name());

    private final SearchIndexVersionRepository versions;
    private final SearchIndexRebuildRunRepository runs;
    private final ElasticsearchIndexManager indexes;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IndexingProperties properties;

    public IndexVersionWriteService(SearchIndexVersionRepository versions,
                                    SearchIndexRebuildRunRepository runs,
                                    ElasticsearchIndexManager indexes, JdbcTemplate jdbc,
                                    PlatformTransactionManager transactionManager,
                                    IndexingProperties properties) {
        this.versions = versions;
        this.runs = runs;
        this.indexes = indexes;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.properties = properties;
    }

    public SearchIndexVersion enable(int versionNumber) {
        requireMutationsEnabled();
        SearchIndexVersion observed = versions.findByVersionNumber(versionNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown index version: " + versionNumber));
        requireEnableable(observed);
        requireNoPendingTargets(versionNumber);
        // 写入仍关闭，没有实时流量；此时清空物理索引不会与双写竞争
        recreate(observed);
        return transactions.execute(status -> {
            SearchIndexVersion locked = versions.findByVersionNumberForUpdate(versionNumber)
                    .orElseThrow(() -> new IllegalStateException("index version disappeared"));
            requireEnableable(locked);
            locked.startWriteSession(scalar(
                    "SELECT COALESCE(MAX(id), 0) FROM search_index_change_event"));
            return locked;
        });
    }

    public SearchIndexVersion disable(int versionNumber) {
        requireMutationsEnabled();
        return transactions.execute(status -> {
            SearchIndexVersion locked = versions.findByVersionNumberForUpdate(versionNumber)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown index version: " + versionNumber));
            if (locked.getDeletedAt() != null) {
                throw new IllegalStateException("deleted version cannot change writes");
            }
            requireIdle(versionNumber);
            locked.endWriteSession();
            return locked;
        });
    }

    private void requireEnableable(SearchIndexVersion version) {
        if (version.getDeletedAt() != null) {
            throw new IllegalStateException("deleted version cannot enable writes");
        }
        if (version.isWriteEnabled()) {
            throw new IllegalStateException(
                    "version " + version.getVersionNumber() + " already accepts writes");
        }
        if (!version.isPipelineSupported()) {
            throw new IllegalStateException("version pipeline is unsupported by this deployment");
        }
        if (version.getNeedsAttentionReason() != null) {
            throw new IllegalStateException("version needs attention before enabling writes");
        }
        requireIdle(version.getVersionNumber());
    }

    private void requireIdle(int versionNumber) {
        if (runs.existsByVersionNumberAndStateIn(versionNumber, ACTIVE_RUN_STATES)) {
            throw new IllegalStateException(
                    "version " + versionNumber + " has an active migration");
        }
    }

    /** 上一次写入会话遗留的任务若在清空后才执行，会把旧数据写回新索引。 */
    private void requireNoPendingTargets(int versionNumber) {
        Long pending = jdbc.queryForObject("""
                SELECT COUNT(*) FROM indexing_job_target
                WHERE target_version=? AND state IN ('PENDING','LEASED','RETRY_WAIT')
                """, Long.class, versionNumber);
        if (pending != null && pending > 0) {
            throw new IllegalStateException(
                    "version " + versionNumber + " still has pending indexing jobs");
        }
    }

    private void recreate(SearchIndexVersion version) {
        EditableIndexConfig config = version.editableConfig();
        if (config.mappingSchemaVersion() >= 3) {
            indexes.recreateOfflineVersion(version.getPhysicalName(),
                    config.embeddingDimensions(), config.mappingSchemaVersion());
        } else {
            indexes.recreateOfflineVersion(version.getPhysicalName(),
                    config.embeddingDimensions());
        }
    }

    private long scalar(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private void requireMutationsEnabled() {
        if (!Boolean.TRUE.equals(properties.management().mutationsEnabled())) {
            throw new IllegalStateException(SearchIndexAdminService.MUTATIONS_DISABLED_MESSAGE);
        }
    }
}
```

注：若 `RebuildRunState` 没有 `PENDING` 常量，改用字符串 `"PENDING"`（`JpaRebuildRunRegistry` 中即如此使用）。

- [ ] **Step 8: 运行测试确认通过**

Run: `./mvnw -q test -Dtest=IndexVersionWriteServiceTest`
Expected: 6 个测试全部 PASS。

- [ ] **Step 9: 提交**

```bash
git add src/main/resources/db/migration/V34__index_write_session.sql src/main/java/com/kwiki/indexing/version/SearchIndexVersion.java src/main/java/com/kwiki/indexing/version/IndexVersionWriteService.java src/test/java/com/kwiki/indexing/version/IndexVersionWriteServiceTest.java
git commit -m "feat(indexing): 新增索引版本写入会话与手动写入开关服务"
```

---

### Task 2: 存量迁移 run（MIGRATION）

**Files:**
- Modify: `src/main/java/com/kwiki/indexing/version/RebuildRunKind.java`
- Modify: `src/main/java/com/kwiki/indexing/version/IndexVersionStatusPolicy.java`
- Modify: `src/main/java/com/kwiki/indexing/version/JpaRebuildRunRegistry.java`
- Modify: `src/main/java/com/kwiki/indexing/version/FixedRangeRebuildScanner.java`
- Modify: `src/main/java/com/kwiki/indexing/version/VersionRebuildCoordinator.java`
- Create: `src/main/java/com/kwiki/indexing/version/IndexMigrationService.java`
- Test: `src/test/java/com/kwiki/indexing/version/IndexMigrationServiceTest.java`
- Test: `src/test/java/com/kwiki/indexing/version/FixedRangeRebuildScannerMigrationTest.java`
- Test: `src/test/java/com/kwiki/indexing/version/IndexVersionStatusPolicyTest.java`（追加）

**Interfaces:**
- Consumes: Task 1 的 `getWriteEnabledEventId()`、`startWriteSession(long)`。
- Produces:
  - `RebuildRunKind.MIGRATION`
  - `IndexVersionStatusPolicy.startMigration(IndexVersionSnapshot)`、`completeMigration(IndexVersionSnapshot, long builtRevision)`
  - `VersionRebuildCoordinator#startMigration(Request, RebuildWork): StartResult`
  - `IndexMigrationService#migrate(int versionNumber, String requestedBy): VersionRebuildCoordinator.StartResult`
  - MIGRATION run 的 `buildStartEventId` == 发起时版本的 `writeEnabledEventId`（E）

- [ ] **Step 1: 追加策略失败测试**

在 `IndexVersionStatusPolicyTest` 类末尾追加（复用文件已有的 `snapshot(...)` 工厂）：

```java
    @Test
    void 写入关闭时不能开始存量迁移() {
        assertThatThrownBy(() -> IndexVersionStatusPolicy.startMigration(snapshot(1, null,
                IndexBuildState.NEW, IndexCatchupStatus.BEHIND, false, false, true,
                false, false, null, false))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 开始存量迁移_进入构建中且标记活动() {
        IndexVersionSnapshot started = IndexVersionStatusPolicy.startMigration(snapshot(1, null,
                IndexBuildState.NEW, IndexCatchupStatus.BEHIND, true, false, true,
                false, false, null, false));
        assertThat(started.buildState()).isEqualTo(IndexBuildState.BUILDING);
        assertThat(started.activeRun()).isTrue();
    }

    @Test
    void 存量迁移完成_已构建且追平() {
        IndexVersionSnapshot done = IndexVersionStatusPolicy.completeMigration(snapshot(1, null,
                IndexBuildState.BUILDING, IndexCatchupStatus.BEHIND, true, false, true,
                true, false, null, false), 1);
        assertThat(done.buildState()).isEqualTo(IndexBuildState.BUILT);
        assertThat(done.builtConfigRevision()).isEqualTo(1L);
        assertThat(done.catchupStatus()).isEqualTo(IndexCatchupStatus.CURRENT);
        assertThat(done.activeRun()).isFalse();
    }

    @Test
    void 存量迁移完成时写入已关闭_保持落后() {
        IndexVersionSnapshot done = IndexVersionStatusPolicy.completeMigration(snapshot(1, null,
                IndexBuildState.BUILDING, IndexCatchupStatus.BEHIND, false, false, true,
                true, false, null, false), 1);
        assertThat(done.catchupStatus()).isEqualTo(IndexCatchupStatus.BEHIND);
        assertThat(done.buildState()).isEqualTo(IndexBuildState.FAILED);
    }
```

- [ ] **Step 2: 写迁移服务失败测试**

`src/test/java/com/kwiki/indexing/version/IndexMigrationServiceTest.java`：

```java
package com.kwiki.indexing.version;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IndexMigrationServiceTest {

    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final VersionRebuildCoordinator coordinator = mock(VersionRebuildCoordinator.class);
    private final FixedRangeRebuildScanner scanner = mock(FixedRangeRebuildScanner.class);
    private final EditableIndexConfig config =
            new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 3);
    private IndexMigrationService service;
    private SearchIndexVersion version;

    @BeforeEach
    void setUp() {
        service = new IndexMigrationService(versions, coordinator, scanner,
                IndexVersionWriteServiceTest.properties(true), null);
        version = new SearchIndexVersion(2, "kwiki-chunks-v2", config,
                new ChunkMappingBuilder().mappingHash(1024, 3));
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(version));
    }

    @Test
    void 写入关闭时拒绝迁移_不发起run() {
        assertThatThrownBy(() -> service.migrate(2, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enable writes");
        verifyNoInteractions(coordinator);
    }

    @Test
    void 写入开启时发起MIGRATION_run() {
        version.startWriteSession(42L);
        when(coordinator.startMigration(any(), any())).thenReturn(
                new VersionRebuildCoordinator.StartResult(true, 9L, false, "ACCEPTED"));

        VersionRebuildCoordinator.StartResult result = service.migrate(2, "admin");

        ArgumentCaptor<VersionRebuildCoordinator.Request> request =
                ArgumentCaptor.forClass(VersionRebuildCoordinator.Request.class);
        verify(coordinator).startMigration(request.capture(), any());
        assertThat(request.getValue().kind()).isEqualTo(RebuildRunKind.MIGRATION);
        assertThat(request.getValue().versionNumber()).isEqualTo(2);
        assertThat(result.runId()).isEqualTo(9L);
    }

    @Test
    void 已迁移版本拒绝再次迁移() {
        SearchIndexVersion published = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config,
                new ChunkMappingBuilder().mappingHash(1024, 3));
        when(versions.findByVersionNumber(1)).thenReturn(Optional.of(published));
        assertThatThrownBy(() -> service.migrate(1, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already migrated");
    }
}
```

- [ ] **Step 3: 写扫描器截止过滤失败测试**

`src/test/java/com/kwiki/indexing/version/FixedRangeRebuildScannerMigrationTest.java`：

```java
package com.kwiki.indexing.version;

import com.kwiki.indexing.config.IndexingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FixedRangeRebuildScannerMigrationTest {

    private final SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
    private final SearchIndexRebuildRangeRepository ranges = mock(SearchIndexRebuildRangeRepository.class);
    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RebuildTargetEnqueuer enqueuer = mock(RebuildTargetEnqueuer.class);
    private final IndexingProperties properties = IndexVersionWriteServiceTest.properties(true);
    private final FixedRangeRebuildScanner scanner = new FixedRangeRebuildScanner(runs, ranges,
            versions, jdbc, enqueuer, mock(PlatformTransactionManager.class), properties);

    @SuppressWarnings("unchecked")
    private String capturePageSql(Long cutoffEventId, List<Object> argsOut) {
        SearchIndexRebuildRange range = new SearchIndexRebuildRange(7L, "PAGE", 1, 100);
        when(ranges.findByIdForUpdate(anyLong())).thenReturn(Optional.of(range));
        when(runs.findById(7L)).thenReturn(Optional.of(mock(SearchIndexRebuildRun.class)));
        // 用 thenAnswer 记录原始参数，避免 Mockito 5 对可变参数捕获的歧义
        List<Object[]> calls = new java.util.ArrayList<>();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            calls.add(invocation.getRawArguments());
            return List.of();
        });

        scanner.processBatch(7L, 1L, 2, "kwiki-chunks-v2", false, cutoffEventId);

        Object[] raw = calls.get(0);
        argsOut.addAll(java.util.Arrays.asList((Object[]) raw[2]));
        return (String) raw[0];
    }

    @Test
    void 迁移模式_跳过双写起点之后有变更事件的资源() {
        List<Object> args = new java.util.ArrayList<>();
        String sql = capturePageSql(42L, args);
        assertThat(sql).contains("NOT EXISTS").contains("search_index_change_event").contains("e.id>?");
        assertThat(args).contains(42L);
    }

    @Test
    void 非迁移模式_不加截止过滤() {
        List<Object> args = new java.util.ArrayList<>();
        String sql = capturePageSql(null, args);
        assertThat(sql).doesNotContain("search_index_change_event");
    }
}
```

注：`SearchIndexRebuildRange` 的构造器签名以现有代码为准（`JpaRebuildRunRegistry` 中为 `new SearchIndexRebuildRange(runId, "PAGE", min, max)`）。

- [ ] **Step 4: 运行测试确认失败**

Run: `./mvnw -q test -Dtest='IndexVersionStatusPolicyTest,IndexMigrationServiceTest,FixedRangeRebuildScannerMigrationTest'`
Expected: 编译失败（`startMigration`、`MIGRATION`、`IndexMigrationService`、`processBatch` 可见性/签名不符）。

- [ ] **Step 5: 新增 run 种类**

`RebuildRunKind.java`：在枚举常量列表末尾追加 `MIGRATION`（保留 `INITIAL`、`MANUAL` 以兼容历史 run 行），并在枚举上方注释补一句：`MIGRATION：写入开启后按版本解析器补入双写起点之前的历史数据。`

- [ ] **Step 6: 策略新增迁移函数**

在 `IndexVersionStatusPolicy` 中 `failBuild` 之后加入：

```java
    /** 存量迁移开始：必须已开启写入（双写先于迁移），置 BUILDING 并标记活动。 */
    public static IndexVersionSnapshot startMigration(IndexVersionSnapshot snapshot) {
        if (!snapshot.writeEnabled()) {
            throw new IllegalStateException("version " + snapshot.versionNumber()
                    + " must accept writes before migration");
        }
        if (snapshot.deleted() || !snapshot.pipelineSupported() || snapshot.activeRun()) {
            throw new IllegalStateException(
                    "version " + snapshot.versionNumber() + " cannot start migration");
        }
        return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                snapshot.configRevision(), snapshot.builtConfigRevision(),
                IndexBuildState.BUILDING, IndexCatchupStatus.BEHIND, true,
                snapshot.selected(), snapshot.pipelineSupported(), snapshot.deleted(),
                snapshot.needsAttentionReason(), true, snapshot.switchPreparing());
    }

    /**
     * 存量迁移完成：历史数据已补入且双写持续进行，因此直接追平（CURRENT）。
     * 若期间写入被关闭或配置被换（防御路径），视为失败，需重新开启写入后再迁移。
     */
    public static IndexVersionSnapshot completeMigration(IndexVersionSnapshot snapshot,
                                                         long builtRevision) {
        if (!snapshot.writeEnabled() || builtRevision != snapshot.configRevision()) {
            return failBuild(snapshot);
        }
        return new IndexVersionSnapshot(snapshot.versionNumber(), snapshot.physicalName(),
                snapshot.configRevision(), builtRevision, IndexBuildState.BUILT,
                IndexCatchupStatus.CURRENT, true, snapshot.selected(),
                snapshot.pipelineSupported(), snapshot.deleted(),
                snapshot.needsAttentionReason(), false, snapshot.switchPreparing());
    }
```

（`failBuild` 保持 `catchupStatus` 不变——此时为 BEHIND，满足「保持落后」的测试。）

- [ ] **Step 7: 运行时注册表支持 MIGRATION**

`JpaRebuildRunRegistry.claimOrCreate`：把

```java
        IndexVersionSnapshot building = IndexVersionStatusPolicy.startBuild(
                version.toSnapshot(false, false));
        BuildManifestSnapshot manifest = version.buildManifestSnapshot();
        long eventId = scalar("SELECT COALESCE(MAX(id), 0) FROM search_index_change_event");
```

替换为：

```java
        boolean migration = request.kind() == RebuildRunKind.MIGRATION;
        IndexVersionSnapshot building = migration
                ? IndexVersionStatusPolicy.startMigration(version.toSnapshot(false, false))
                : IndexVersionStatusPolicy.startBuild(version.toSnapshot(false, false));
        BuildManifestSnapshot manifest = version.buildManifestSnapshot();
        // 迁移 run 的起点固化为写入会话起点 E：扫描只补入 E 之后没有再变更的资源
        long eventId;
        if (migration) {
            if (version.getWriteEnabledEventId() == null) {
                throw new IllegalStateException("version has no write session to migrate into");
            }
            eventId = version.getWriteEnabledEventId();
        } else {
            eventId = scalar("SELECT COALESCE(MAX(id), 0) FROM search_index_change_event");
        }
```

`complete(...)` 中把 `version.applySnapshot(IndexVersionStatusPolicy.completeBuild(...))` 改为：

```java
        versions.findByVersionNumberForUpdate(run.getVersionNumber()).ifPresent(version -> {
            IndexVersionSnapshot current = version.toSnapshot(true, false);
            if (run.kind() == RebuildRunKind.MIGRATION) {
                // 迁移期间写入会话被重开（E 变化）时，本次结果不属于当前会话
                boolean sameSession = java.util.Objects.equals(
                        version.getWriteEnabledEventId(), run.getBuildStartEventId());
                version.applySnapshot(sameSession
                        ? IndexVersionStatusPolicy.completeMigration(current, run.getConfigRevision())
                        : IndexVersionStatusPolicy.failBuild(current));
            } else {
                version.applySnapshot(IndexVersionStatusPolicy.completeBuild(
                        current, run.getConfigRevision()));
            }
        });
```

`attachmentBound(...)` 的类型列表补上 PDF（多模态解析代会扫描 PDF 附件，旧上界漏掉了它们；上界取超集是安全的，扫描器仍按版本过滤类型）：

```java
                + "('image/png','image/jpeg','image/gif','image/webp','application/pdf')");
```

- [ ] **Step 8: 扫描器迁移模式**

`FixedRangeRebuildScanner`：

1. `scan(...)` 中计算截止点并传给批处理：

```java
        // 迁移 run：只补入写入会话起点 E 之后没有再变更的资源，其余由双写负责
        Long cutoffEventId = run.kind() == RebuildRunKind.MIGRATION
                ? run.getBuildStartEventId() : null;
```

循环体改为：

```java
                transactions.executeWithoutResult(ignored -> processBatch(
                        run.getId(), rangeId, version.getVersionNumber(),
                        version.getPhysicalName(), multimodalParser, cutoffEventId));
```

2. `processBatch` 改为包内可见并增加参数：

```java
    void processBatch(long runId, long rangeId, int version, String physicalName,
                      boolean multimodalParser, Long cutoffEventId) {
        SearchIndexRebuildRange range = ranges.findByIdForUpdate(rangeId).orElseThrow();
        SearchIndexRebuildRun run = runs.findById(runId).orElseThrow();
        List<ResourceRow> rows = switch (range.getResourceType()) {
            case "PAGE" -> pageRows(range, version, cutoffEventId);
            case "ATTACHMENT" -> attachmentRows(range, multimodalParser, version, cutoffEventId);
            default -> throw new IllegalStateException(
                    "unsupported rebuild resource type: " + range.getResourceType());
        };
        // 其余不变
```

3. 新增截止片段与参数组装，并改写两个查询：

```java
    /** 资源在 E 之后仍有变更事件时由双写负责，迁移跳过它。 */
    private static String cutoffClause(String resourceType, String idColumn, Long cutoffEventId) {
        return cutoffEventId == null ? "" : """
                  AND NOT EXISTS (SELECT 1 FROM search_index_change_event e
                    WHERE e.resource_type='%s' AND e.resource_id=%s AND e.id>?)
                """.formatted(resourceType, idColumn);
    }

    private Object[] args(SearchIndexRebuildRange range, Long cutoffEventId, int version) {
        List<Object> values = new java.util.ArrayList<>();
        values.add(range.getLastSeenId());
        values.add(range.getMaxId());
        if (cutoffEventId != null) values.add(cutoffEventId);
        values.add(version);
        values.add(version);
        values.add(batchSize);
        return values.toArray();
    }

    private List<ResourceRow> pageRows(SearchIndexRebuildRange range, int version,
                                       Long cutoffEventId) {
        return jdbc.query("""
                SELECT p.id, p.current_published_revision_id, p.lifecycle_version
                FROM wiki_page p JOIN knowledge_base k ON k.id=p.kb_id
                WHERE p.id>? AND p.id<=? AND p.node_type='PAGE' AND p.status='ACTIVE'
                  AND p.current_published_revision_id IS NOT NULL AND k.status='ACTIVE'
                """ + cutoffClause("PAGE", "p.id", cutoffEventId)
                + com.kwiki.indexing.gray.IndexVersionKbScope.sqlFilter("p.kb_id") + """
                 ORDER BY p.id ASC LIMIT ?
                """, (rs, n) -> new ResourceRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                args(range, cutoffEventId, version));
    }

    private List<ResourceRow> attachmentRows(SearchIndexRebuildRange range,
                                             boolean multimodalParser, int version,
                                             Long cutoffEventId) {
        // 多模态解析代把普通 PDF 附件纳入基线，导入来源由页面索引承载。
        String types = multimodalParser
                ? "('image/png','image/jpeg','image/gif','image/webp','application/pdf')"
                : "('image/png','image/jpeg','image/gif','image/webp')";
        return jdbc.query("""
                SELECT a.id FROM attachment a JOIN knowledge_base k ON k.id=a.kb_id
                WHERE a.id>? AND a.id<=? AND a.status='STORED' AND k.status='ACTIVE'
                  AND a.purpose='GENERAL'
                  AND LOWER(a.content_type) IN %s
                """.formatted(types) + cutoffClause("ATTACHMENT", "a.id", cutoffEventId)
                + com.kwiki.indexing.gray.IndexVersionKbScope.sqlFilter("a.kb_id") + """
                 ORDER BY a.id ASC LIMIT ?
                """, (rs, n) -> new ResourceRow(rs.getLong(1), null, 0),
                args(range, cutoffEventId, version));
    }
```

注意 `IndexVersionKbScope.sqlFilter` 生成的两个 `?` 必须排在截止 `?` 之后——`args(...)` 的顺序即 SQL 中占位符的顺序。

- [ ] **Step 9: 协调器入口**

`VersionRebuildCoordinator` 在 `startManual` 之后加：

```java
    public StartResult startMigration(Request request, RebuildWork work) {
        return start(requireKind(request, RebuildRunKind.MIGRATION), work);
    }
```

- [ ] **Step 10: 新建迁移服务**

`src/main/java/com/kwiki/indexing/version/IndexMigrationService.java`：

```java
package com.kwiki.indexing.version;

import com.kwiki.graph.persistence.GraphBuildRepository;
import com.kwiki.indexing.config.IndexingProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 存量迁移：写入开启后，按版本配置的解析器从原始数据（已发布修订、导入 PDF 源文件、
 * 附件原文件）重建写入会话起点之前的历史内容。写入未开启时一律拒绝——双写必须先于迁移。
 */
@Service
public class IndexMigrationService {
    private final SearchIndexVersionRepository versions;
    private final VersionRebuildCoordinator coordinator;
    private final FixedRangeRebuildScanner scanner;
    private final IndexingProperties properties;
    private final GraphBuildRepository graphBuilds;

    @Autowired
    public IndexMigrationService(SearchIndexVersionRepository versions,
                                 VersionRebuildCoordinator coordinator,
                                 FixedRangeRebuildScanner scanner,
                                 IndexingProperties properties,
                                 @Nullable GraphBuildRepository graphBuilds) {
        this.versions = versions;
        this.coordinator = coordinator;
        this.scanner = scanner;
        this.properties = properties;
        this.graphBuilds = graphBuilds;
    }

    public VersionRebuildCoordinator.StartResult migrate(int versionNumber, String requestedBy) {
        requireMutationsEnabled();
        requireEligible(version(versionNumber), false);
        return coordinator.startMigration(new VersionRebuildCoordinator.Request(
                versionNumber, RebuildRunKind.MIGRATION, requestedBy), run -> {
            SearchIndexVersion admitted = version(run.getVersionNumber());
            requireEligible(admitted, true);
            if (admitted.getConfigRevision() != run.getConfigRevision()) {
                throw new IllegalStateException("version configuration changed after run admission");
            }
            if (!Objects.equals(admitted.getWriteEnabledEventId(), run.getBuildStartEventId())) {
                throw new IllegalStateException("write session changed after run admission");
            }
            scanner.scan(run);
        });
    }

    private SearchIndexVersion version(int versionNumber) {
        return versions.findByVersionNumber(versionNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown index version: " + versionNumber));
    }

    private void requireEligible(SearchIndexVersion version, boolean admitted) {
        if (version.getDeletedAt() != null) {
            throw new IllegalStateException("deleted version cannot be migrated");
        }
        if (!version.isWriteEnabled() || version.getWriteEnabledEventId() == null) {
            throw new IllegalStateException("enable writes before migrating this version");
        }
        if (!version.isPipelineSupported()) {
            throw new IllegalStateException("version pipeline is unsupported by this deployment");
        }
        if (graphBuilds != null
                && graphBuilds.hasActiveRunReferencingChunkIndex(version.getVersionNumber())) {
            throw new IllegalStateException("version is referenced by an active graph build");
        }
        if (!admitted) {
            IndexVersionSnapshot snapshot = version.toSnapshot(false, false);
            if (!snapshot.dirty() && snapshot.buildState() == IndexBuildState.BUILT
                    && snapshot.catchupStatus() == IndexCatchupStatus.CURRENT) {
                throw new IllegalStateException("version is already migrated");
            }
            IndexVersionStatusPolicy.startMigration(snapshot);
        }
    }

    private void requireMutationsEnabled() {
        if (!Boolean.TRUE.equals(properties.management().mutationsEnabled())) {
            throw new IllegalStateException(SearchIndexAdminService.MUTATIONS_DISABLED_MESSAGE);
        }
    }
}
```

- [ ] **Step 11: 运行测试确认通过**

Run: `./mvnw -q test -Dtest='IndexVersionStatusPolicyTest,IndexMigrationServiceTest,FixedRangeRebuildScannerMigrationTest,VersionRebuildCoordinatorTest'`
Expected: 全部 PASS。

- [ ] **Step 12: 提交**

```bash
git add src/main/java/com/kwiki/indexing/version src/test/java/com/kwiki/indexing/version
git commit -m "feat(indexing): 新增存量迁移 run，只补入双写起点之前的历史数据"
```

---

### Task 3: 校验与选择改为基于迁移

**Files:**
- Modify: `src/main/java/com/kwiki/indexing/version/SearchIndexValidationService.java`
- Modify: `src/main/java/com/kwiki/indexing/version/SearchIndexSelectionRegistry.java`
- Test: `src/test/java/com/kwiki/indexing/version/SearchIndexValidationSyncTest.java`（新建）
- Test: `src/test/java/com/kwiki/indexing/version/SearchIndexSelectionRegistryTest.java`（新建）

**Interfaces:**
- Consumes: Task 2 的 MIGRATION run（`kind()`、`getBuildStartEventId()`）与 `getWriteEnabledEventId()`。
- Produces: `SearchIndexValidationService#synchronizedState(SearchIndexRebuildRun, SearchIndexVersion, long barrierEventId): boolean`（包内可见）；`currentReadyReport` 语义不变（仍被 `AliasSwitchService` 与灰度使用）。

- [ ] **Step 1: 写校验同步判断的失败测试**

`src/test/java/com/kwiki/indexing/version/SearchIndexValidationSyncTest.java`：

```java
package com.kwiki.indexing.version;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.search.ElasticsearchIndexManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchIndexValidationSyncTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SearchIndexValidationService service = new SearchIndexValidationService(
            mock(SearchIndexVersionRepository.class), mock(SearchIndexRebuildRunRepository.class),
            mock(SearchIndexRebuildRangeRepository.class),
            mock(SearchIndexValidationReportRepository.class),
            mock(ElasticsearchIndexManager.class), mock(ChunkMappingBuilder.class),
            new ObjectMapper(), jdbc);
    private final SearchIndexRebuildRun run = mock(SearchIndexRebuildRun.class);
    private final SearchIndexVersion version = mock(SearchIndexVersion.class);

    @BeforeEach
    void setUp() {
        when(run.getId()).thenReturn(9L);
        when(run.getVersionNumber()).thenReturn(2);
        when(run.kind()).thenReturn(RebuildRunKind.MIGRATION);
        when(run.state()).thenReturn(RebuildRunState.COMPLETED);
        when(run.getBuildStartEventId()).thenReturn(42L);
        when(version.isWriteEnabled()).thenReturn(true);
        when(version.getWriteEnabledEventId()).thenReturn(42L);
        when(version.getCatchupStatus()).thenReturn(IndexCatchupStatus.CURRENT.name());
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(0L);
    }

    @Test
    void 本会话迁移完成且双写积压清零_视为已同步() {
        assertThat(service.synchronizedState(run, version, 100L)).isTrue();
    }

    @Test
    void 旧的重建run不算同步() {
        when(run.kind()).thenReturn(RebuildRunKind.MANUAL);
        assertThat(service.synchronizedState(run, version, 100L)).isFalse();
    }

    @Test
    void 写入会话已重开_旧迁移不算同步() {
        when(version.getWriteEnabledEventId()).thenReturn(77L);
        assertThat(service.synchronizedState(run, version, 100L)).isFalse();
    }

    @Test
    void 双写仍有积压_不算同步() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(), any(), any())).thenReturn(5L);
        assertThat(service.synchronizedState(run, version, 100L)).isFalse();
    }
}
```

- [ ] **Step 2: 写选择注册表失败测试**

`src/test/java/com/kwiki/indexing/version/SearchIndexSelectionRegistryTest.java`：

```java
package com.kwiki.indexing.version;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SearchIndexSelectionRegistryTest {

    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final SearchIndexSelectionRegistry registry = new SearchIndexSelectionRegistry(versions);
    private final EditableIndexConfig config =
            new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "model", 1024, 3);
    private final String hash = new ChunkMappingBuilder().mappingHash(1024, 3);

    @Test
    void 选择版本不再开启其他版本写入() {
        SearchIndexVersion v1 = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config, hash);
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        v2.startWriteSession(42L);
        SearchIndexVersion v3 = new SearchIndexVersion(3, "kwiki-chunks-v3", config, hash);
        when(versions.findAllActiveForUpdate()).thenReturn(List.of(v1, v2, v3));

        registry.select(2);

        assertThat(v2.isSelected()).isTrue();
        assertThat(v1.isSelected()).isFalse();
        assertThat(v3.isWriteEnabled()).isFalse();
    }

    @Test
    void 写入关闭的目标拒绝选择() {
        SearchIndexVersion v1 = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config, hash);
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        when(versions.findAllActiveForUpdate()).thenReturn(List.of(v1, v2));
        assertThatThrownBy(() -> registry.select(2)).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./mvnw -q test -Dtest='SearchIndexValidationSyncTest,SearchIndexSelectionRegistryTest'`
Expected: 编译失败（`synchronizedState` 签名不符），选择测试中 `v3.isWriteEnabled()` 为 true 导致失败。

- [ ] **Step 4: 改写校验同步判断**

`SearchIndexValidationService`：

1. `validate(...)` 中，在 `boolean synchronizedState=synchronizedState(run);` 之前捕获屏障，并替换该行：

```java
        // 屏障：校验时刻的事件水位；此前的双写目标都必须已完成
        long barrier=scalarLong("SELECT COALESCE(MAX(id),0) FROM search_index_change_event");
        boolean synchronizedState=synchronizedState(run,version,barrier);
```

2. 构造 `SearchIndexValidationReport` 时，把实参 `run==null?null:run.getCatchupBarrierEventId()` 改为 `barrier`。

3. `currentReadyReport` 中把

```java
        if(run==null||run.switchState()!=IndexSwitchState.READY
                ||!Objects.equals(report.getBarrierEventId(),run.getCatchupBarrierEventId())
                ||!Objects.equals(report.getCursorFingerprint(),cursorFingerprint(run))
                ||!Objects.equals(report.getAliasFingerprint(),aliasFingerprint())
                ||!synchronizedState(run))return Optional.empty();
```

替换为：

```java
        if(run==null||report.getBarrierEventId()==null
                ||!Objects.equals(report.getCursorFingerprint(),cursorFingerprint(run))
                ||!Objects.equals(report.getAliasFingerprint(),aliasFingerprint())
                ||!synchronizedState(run,version,report.getBarrierEventId()))return Optional.empty();
```

4. 替换 `synchronizedState(SearchIndexRebuildRun run)` 与 `cursorFingerprint`：

```java
    /** 本写入会话的迁移已完成、版本已追平，且屏障前的双写目标与迁移目标全部完成。 */
    boolean synchronizedState(SearchIndexRebuildRun run,SearchIndexVersion version,long barrier){
        if(run==null||run.kind()!=RebuildRunKind.MIGRATION
                ||run.state()!=RebuildRunState.COMPLETED||!version.isWriteEnabled()
                ||!Objects.equals(version.getWriteEnabledEventId(),run.getBuildStartEventId())
                ||!IndexCatchupStatus.CURRENT.name().equals(version.getCatchupStatus()))return false;
        Long unresolved=jdbc.queryForObject("""
                SELECT COUNT(*) FROM indexing_job_target t JOIN indexing_job j ON j.id=t.job_id
                WHERE t.target_version=? AND t.state<>'COMPLETED'
                  AND (t.event_id<=? OR j.idempotency_key LIKE ?)
                """,Long.class,run.getVersionNumber(),barrier,"REBUILD:"+run.getId()+":%");
        return unresolved!=null&&unresolved==0;
    }

    /** 报告与写入会话绑定：会话重开（E 变化）后旧报告自动失效。 */
    private String cursorFingerprint(SearchIndexRebuildRun run){
        return run==null?null:"write-session:"+run.getBuildStartEventId();
    }

    private long scalarLong(String sql){
        Long value=jdbc.queryForObject(sql,Long.class);
        return value==null?0L:value;
    }
```

`ranges` 字段与构造参数保留不动（其他测试依赖该构造签名）；删除后若 IDE 提示 `ranges` 未使用可忽略。去掉不再使用的 `IndexSwitchState` 引用。

- [ ] **Step 5: 改写选择注册表**

`SearchIndexSelectionRegistry.java` 整体替换为：

```java
package com.kwiki.indexing.version;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** ES 别名切换成功后的数据库收敛；只改变读目标，写入集合完全由管理员的写入开关决定。 */
@Service
public class SearchIndexSelectionRegistry {
    private final SearchIndexVersionRepository versions;
    public SearchIndexSelectionRegistry(SearchIndexVersionRepository versions){this.versions=versions;}

    @Transactional
    public void select(int targetVersion){
        var all=versions.findAllActiveForUpdate();
        SearchIndexVersion target=all.stream().filter(v->v.getVersionNumber()==targetVersion)
                .findFirst().orElseThrow(()->new IllegalStateException("switch target disappeared"));
        if(!target.isWriteEnabled())
            throw new IllegalStateException("target version must accept writes before selection");
        for(SearchIndexVersion version:all){
            if(version.isSelected()) version.applySnapshot(IndexVersionStatusPolicy.unpublish(
                    version.toSnapshot(false,false)));
        }
        target.applySnapshot(IndexVersionStatusPolicy.publish(target.toSnapshot(false,false)));
    }
}
```

`publish` 会把 `writeEnabled` 置 true——目标已开启写入，不改变事实；`writeEnabledEventId` 不在快照中，保持原值。

- [ ] **Step 6: 运行测试确认通过**

Run: `./mvnw -q test -Dtest='SearchIndexValidationSyncTest,SearchIndexSelectionRegistryTest,SearchIndexValidationServiceTest'`
Expected: 新测试 PASS。`SearchIndexValidationServiceTest` 中依赖 `switchState=READY`、`startSwitchPreparation`、`captureCatchupBarrier` 的用例会失败：把这些用例的前置改为「mock/构造一个 `kind()==MIGRATION`、`state()==COMPLETED`、`getBuildStartEventId()` 等于版本 `writeEnabledEventId` 的 run，版本 `catchupStatus=CURRENT`」，断言保持原意（PASS / FAIL 条件不变）；专门测试尾扫游标、补齐屏障的用例直接删除。修改后重跑直到全部 PASS。

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/kwiki/indexing/version src/test/java/com/kwiki/indexing/version
git commit -m "refactor(indexing): 校验按写入会话的迁移结果判断同步，选择版本不再隐式开启写入"
```

---

### Task 4: 灰度流程改为「开启双写 → 存量迁移 → 校验」

**Files:**
- Modify: `src/main/java/com/kwiki/indexing/gray/GrayReleaseService.java`
- Modify: `src/main/java/com/kwiki/indexing/gray/GrayReleaseSyncDriver.java`（仅注释）
- Test: `src/test/java/com/kwiki/indexing/gray/GrayReleaseServiceTest.java`

**Interfaces:**
- Consumes: `IndexVersionWriteService#enable/disable`（Task 1）、`IndexMigrationService#migrate`（Task 2）、`SearchIndexValidationService#currentReadyReport/validate`（Task 3）。
- Produces: 新构造签名 `GrayReleaseService(GrayReleaseStore, SearchIndexAdminService, SearchIndexVersionRepository, IndexVersionKbScope, IndexVersionWriteService, IndexMigrationService, SearchIndexValidationService, SearchIndexRebuildRunRepository, ParserCatalog[, IndexingProperties])`。

- [ ] **Step 1: 改写测试夹具与同步/推进/结束用例**

`GrayReleaseServiceTest`：

1. 字段：删除 `rebuilds`、`preparations`、`enablement` 三个 mock，新增：

```java
    private final IndexVersionWriteService writes = mock(IndexVersionWriteService.class);
    private final IndexMigrationService migrations = mock(IndexMigrationService.class);
```

2. `setUp` 构造改为：

```java
        service = new GrayReleaseService(store, admin, versions, scope, writes, migrations,
                validations, runs, parsers);
```

3. 删除所有引用 `rebuilds`、`preparations`、`enablement`、`IndexSwitchState` 的旧用例（同步/推进/结束相关），以及只为它们服务的辅助方法（如 `versionWrites`、`accepted()`，若不再被使用）。新增下列辅助与用例：

```java
    private SearchIndexVersion grayVersion(boolean writeEnabled, Long eventId) {
        SearchIndexVersion version = mock(SearchIndexVersion.class);
        when(version.getVersionNumber()).thenReturn(2);
        when(version.isWriteEnabled()).thenReturn(writeEnabled);
        when(version.getWriteEnabledEventId()).thenReturn(eventId);
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(version));
        return version;
    }

    private SearchIndexRebuildRun migrationRun(long startEventId, RebuildRunState state) {
        SearchIndexRebuildRun run = mock(SearchIndexRebuildRun.class);
        when(run.kind()).thenReturn(RebuildRunKind.MIGRATION);
        when(run.getBuildStartEventId()).thenReturn(startEventId);
        when(run.state()).thenReturn(state);
        when(runs.findFirstByVersionNumberOrderByIdDesc(2)).thenReturn(Optional.of(run));
        return run;
    }

    private static VersionRebuildCoordinator.StartResult acceptedMigration() {
        return new VersionRebuildCoordinator.StartResult(true, 9L, false, "ACCEPTED");
    }

    @Test
    void 开始同步_先开启双写再发起存量迁移() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        grayVersion(false, null);
        SearchIndexVersion enabled = mock(SearchIndexVersion.class);
        when(enabled.isWriteEnabled()).thenReturn(true);
        when(enabled.getWriteEnabledEventId()).thenReturn(42L);
        when(writes.enable(2)).thenReturn(enabled);
        when(migrations.migrate(2, "admin")).thenReturn(acceptedMigration());

        GrayRelease syncing = service.sync(created.id(), "admin");

        org.mockito.InOrder order = inOrder(writes, migrations);
        order.verify(writes).enable(2);
        order.verify(migrations).migrate(2, "admin");
        assertThat(syncing.status()).isEqualTo(GrayReleaseStatus.SYNCING);
    }

    @Test
    void 重试同步_写入仍开启时保留双写只重新迁移() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, "同步失败：x");
        grayVersion(true, 42L);
        migrationRun(42L, RebuildRunState.FAILED);
        when(migrations.migrate(2, "admin")).thenReturn(acceptedMigration());

        GrayRelease syncing = service.sync(created.id(), "admin");

        verify(writes, never()).enable(anyInt());
        verify(migrations).migrate(2, "admin");
        assertThat(syncing.lastError()).isNull();
    }

    @Test
    void 推进_双写未开启时拒绝迁移并记录原因() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        grayVersion(false, null);

        GrayRelease advanced = service.advance(created.id());

        verify(migrations, never()).migrate(anyInt(), any());
        assertThat(advanced.lastError()).contains("双写未开启");
    }

    @Test
    void 推进_本会话尚无迁移时发起迁移() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        grayVersion(true, 42L);
        migrationRun(7L, RebuildRunState.COMPLETED); // 上一个写入会话的迁移
        when(migrations.migrate(eq(2), any())).thenReturn(acceptedMigration());

        service.advance(created.id());

        verify(migrations).migrate(eq(2), any());
    }

    @Test
    void 推进_本会话迁移完成后校验_通过即已同步() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        grayVersion(true, 42L);
        migrationRun(42L, RebuildRunState.COMPLETED);
        SearchIndexValidationReport report = mock(SearchIndexValidationReport.class);
        when(report.getStatus()).thenReturn("PASS");
        when(validations.currentReadyReport(2)).thenReturn(Optional.empty());
        when(validations.validate(2)).thenReturn(report);

        GrayRelease advanced = service.advance(created.id());

        verify(migrations, never()).migrate(anyInt(), any());
        assertThat(advanced.status()).isEqualTo(GrayReleaseStatus.SYNCED);
    }

    @Test
    void 推进_本会话迁移失败_记录原因等待重试() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.setStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        grayVersion(true, 42L);
        SearchIndexRebuildRun run = migrationRun(42L, RebuildRunState.FAILED);
        when(run.getErrorSummary()).thenReturn("boom");

        GrayRelease advanced = service.advance(created.id());

        assertThat(advanced.lastError()).contains("boom");
        verify(migrations, never()).migrate(anyInt(), any());
    }

    @Test
    void 结束灰度_关闭写入() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        grayVersion(true, 42L);
        migrationRun(42L, RebuildRunState.COMPLETED);

        service.end(created.id());

        verify(writes).disable(2);
    }

    @Test
    void 结束灰度_迁移进行中拒绝() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        grayVersion(true, 42L);
        migrationRun(42L, RebuildRunState.RUNNING);
        assertThatThrownBy(() -> service.end(created.id())).isInstanceOf(ConflictException.class);
        verify(writes, never()).disable(anyInt());
    }
```

说明：`store.setStatus(id, status, lastError)` 是 `InMemoryGrayReleaseStore` 已有的测试辅助方法（原测试已在用）；`RebuildRunState.RUNNING.active()` 为 true。保留原文件中与创建、切换、切回相关、且不依赖已删除 mock 的用例。

- [ ] **Step 2: 运行测试确认失败**

Run: `./mvnw -q test -Dtest=GrayReleaseServiceTest`
Expected: 编译失败（构造签名不符）。

- [ ] **Step 3: 改写服务**

`GrayReleaseService`：

1. 类注释改为：

```java
/**
 * 灰度发布状态机：创建（建限定范围的索引版本）→ 同步（开启双写 → 存量迁移 → 校验，由 advance 推进）
 * → 切换 / 切回 → 结束（关闭灰度版本写入，索引保留待手动删除）。
 * 存量迁移只在双写开启后发起，截止到双写起点，保证历史与增量之间没有缺口。
 */
```

2. 字段：删除 `rebuilds`、`preparations`、`enablement`，新增：

```java
    private final IndexVersionWriteService writes;
    private final IndexMigrationService migrations;
```

两个构造器的参数列表中 `ManualIndexRebuildService rebuilds, SwitchPreparationService preparations,` 替换为 `IndexVersionWriteService writes, IndexMigrationService migrations,`，删除 `IndexVersionEnablementService enablement,`；构造体与 `this(...)` 委托调用同步调整。

3. 替换 `sync(...)` 整个方法（**去掉 `@Transactional`**：开启写入与发起迁移各有独立事务，且迁移准入会在另一线程锁版本行，外层事务持锁会造成准入超时）：

```java
    /**
     * 开始同步 / 失败重试：写入未开启时先开启双写（清空灰度索引并记录起点 E），
     * 再发起截止到 E 的存量迁移；写入已开启时保留双写，只在本会话没有可用迁移时重新发起。
     */
    public GrayRelease sync(long id, String operator) {
        requireMutationsEnabled();
        GrayRelease release = find(id);
        if (release.status() != GrayReleaseStatus.CREATED && release.status() != GrayReleaseStatus.SYNCING) {
            throw new ConflictException("当前状态不能开始同步：" + release.status());
        }
        int number = release.indexVersionNumber();
        SearchIndexVersion version = versions.findByVersionNumber(number)
                .orElseThrow(() -> new ConflictException("灰度索引版本不存在"));
        if (!version.isWriteEnabled()) {
            try {
                version = writes.enable(number);
            } catch (IllegalStateException failure) {
                throw new ConflictException("开启灰度双写失败：" + safe(failure.getMessage()));
            }
        }
        requireTransition(store.transition(id, release.status(), GrayReleaseStatus.SYNCING, null));
        startMigrationIfNeeded(number, version, operator);
        return find(id);
    }
```

4. 替换 `advance(...)` 与 `advanceCompletedRun(...)`：

```java
    /**
     * 由同步驱动器定时调用：确认双写已开启 → 本会话迁移 → 校验，每次推进一步。
     * 不开外层事务：迁移与校验各有自己的事务。已记录失败原因的灰度停在原地等待用户重试。
     */
    public GrayRelease advance(long id) {
        GrayRelease release = find(id);
        if (release.status() != GrayReleaseStatus.SYNCING || release.lastError() != null) {
            return release;
        }
        int number = release.indexVersionNumber();
        SearchIndexVersion version = versions.findByVersionNumber(number).orElse(null);
        if (version == null || !version.isWriteEnabled() || version.getWriteEnabledEventId() == null) {
            store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCING,
                    "同步失败：灰度索引双写未开启，存量迁移必须在开启双写之后进行。可点击「开始同步」重试");
            return find(id);
        }
        SearchIndexRebuildRun run = sessionRun(number, version.getWriteEnabledEventId()).orElse(null);
        if (run == null) {
            try {
                startMigration(number, release.createdBy());
            } catch (RuntimeException failure) {
                store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCING,
                        "同步失败：" + safe(failure.getMessage()) + "。可点击「开始同步」重试");
            }
            return find(id);
        }
        if (run.state().active()) {
            return release;
        }
        if (run.state() != RebuildRunState.COMPLETED) {
            store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCING,
                    "同步失败：" + safe(run.getErrorSummary()) + "。可点击「开始同步」重试");
            return find(id);
        }
        try {
            validateMigrated(id, number);
        } catch (RuntimeException failure) {
            store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCING,
                    "同步失败：" + safe(failure.getMessage()) + "。可点击「开始同步」重试");
        }
        return find(id);
    }

    private void validateMigrated(long id, int number) {
        if (validations.currentReadyReport(number).isPresent()) {
            store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCED, null);
            return;
        }
        SearchIndexValidationReport report = validations.validate(number);
        if ("PASS".equals(report.getStatus())) {
            store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCED, null);
        } else {
            store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCING,
                    "校验未通过：" + safe(report.getSummary()) + "。如多次重试仍失败，请结束该灰度后重新创建");
        }
    }

    /** 本写入会话（起点 E）发起的最近一次迁移 run；上一个会话的 run 不算。 */
    private java.util.Optional<SearchIndexRebuildRun> sessionRun(int number, long writeEnabledEventId) {
        return runs.findFirstByVersionNumberOrderByIdDesc(number)
                .filter(run -> run.kind() == RebuildRunKind.MIGRATION
                        && run.getBuildStartEventId() == writeEnabledEventId);
    }

    private void startMigrationIfNeeded(int number, SearchIndexVersion version, String operator) {
        SearchIndexRebuildRun run = sessionRun(number, version.getWriteEnabledEventId()).orElse(null);
        if (run != null && (run.state().active() || run.state() == RebuildRunState.COMPLETED)) {
            return;
        }
        startMigration(number, operator);
    }

    private void startMigration(int number, String operator) {
        VersionRebuildCoordinator.StartResult result = migrations.migrate(number, operator);
        if (result == null || !result.accepted()) {
            throw new ConflictException("另一个索引迁移正在进行，请稍后再开始同步");
        }
    }
```

5. `end(...)` 中把 run 检查与停用段替换为：

```java
        runs.findFirstByVersionNumberOrderByIdDesc(number)
                .filter(run -> run.state().active())
                .ifPresent(run -> {
                    throw new ConflictException("同步正在进行，请等待当前迁移结束后再结束灰度");
                });
        versions.findByVersionNumber(number)
                .filter(SearchIndexVersion::isWriteEnabled)
                .ifPresent(version -> {
                    try {
                        writes.disable(version.getVersionNumber());
                    } catch (IllegalStateException justStarted) {
                        // 读取最新 run 之后恰好有迁移开始，关闭写入的空闲检查会拒绝
                        throw new ConflictException("同步刚刚开始，请稍后再结束灰度");
                    }
                });
```

6. 删除不再使用的 import / 引用（`IndexSwitchState`、`ManualIndexRebuildService` 等；本类用 `com.kwiki.indexing.version.*` 通配引入，确认编译即可）。若 `GrayRelease` 记录没有 `createdBy()` 访问器，用其实际的创建人字段名（前端类型中为 `createdBy`）。

- [ ] **Step 4: 同步驱动器注释**

`GrayReleaseSyncDriver.tick()` 的方法注释把「若两个实例同时看到切换状态为 NONE 并各自调用 prepare()，后者会静默重置 dualWriteStartEventId」改为「若两个实例同时看到本会话尚无迁移并各自发起，会重复提交迁移」；类注释改为「定时推进同步中的灰度：确认双写 → 存量迁移 → 校验。单个灰度失败只记录日志。」

- [ ] **Step 5: 运行测试确认通过**

Run: `./mvnw -q test -Dtest='GrayReleaseServiceTest,ScopedIndexingTest'`
Expected: 全部 PASS（`ScopedIndexingTest` 若引用了已删 mock 或 `enableForSwitchPreparation`，按相同替换规则修正：`SwitchPreparationService`→`IndexVersionWriteService`，`prepare(n)`→`enable(n)`）。

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/kwiki/indexing/gray src/test/java/com/kwiki/indexing/gray
git commit -m "refactor(gray): 灰度同步改为先开启双写再存量迁移，最后校验"
```

---

### Task 5: 管理接口、展示状态与可执行动作

**Files:**
- Modify: `src/main/java/com/kwiki/indexing/version/IndexDisplayStatus.java`
- Modify: `src/main/java/com/kwiki/indexing/version/IndexVersionStatusPolicy.java`（`displayStatus`）
- Modify: `src/main/java/com/kwiki/indexing/version/SearchIndexAdminQueryService.java`
- Modify: `src/main/java/com/kwiki/wiki/api/SearchIndexAdminController.java`
- Modify: `src/main/java/com/kwiki/indexing/version/SearchIndexDeletionService.java`
- Modify: `src/main/java/com/kwiki/indexing/version/SearchIndexAdminService.java`（日志文案）
- Test: `IndexVersionStatusPolicyTest`、新建 `SearchIndexAdminActionsTest`、`SearchIndexAdminControllerSecurityTest`、`SearchIndexAdminControllerGrayGuardTest`、`SearchIndexDeletionServiceTest`

**Interfaces:**
- Consumes: Task 1–3 的服务。
- Produces:
  - `IndexDisplayStatus { NEEDS_ATTENTION, MIGRATING, PUBLISHED, PENDING_MIGRATION, MIGRATED }`
  - `VersionView` 末尾新增 `Long writeEnabledEventId, String migrateBlockedReason`
  - `allowedActions` 键：`edit, writeToggle, migrate, validate, select, delete`
  - HTTP：`PUT /api/v1/admin/search-indexes/versions/{n}/write`（body `{"enabled":bool}`），`POST .../versions/{n}/migrate`（202 / 409）

- [ ] **Step 1: 改写展示状态测试**

`IndexVersionStatusPolicyTest.displayStatusFollowsTheDocumentedPriority` 整体替换为：

```java
    @Test
    void displayStatusFollowsTheDocumentedPriority() {
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(3, null,
                IndexBuildState.NEW, IndexCatchupStatus.BEHIND, false, false, true,
                false, false, "alias mismatch", false)))
                .isEqualTo(IndexDisplayStatus.NEEDS_ATTENTION);
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(1, null,
                IndexBuildState.BUILDING, IndexCatchupStatus.BEHIND, true, false, true,
                true, false, null, false)))
                .isEqualTo(IndexDisplayStatus.MIGRATING);
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(2, 2L,
                IndexBuildState.BUILT, IndexCatchupStatus.CURRENT, true, true, true,
                false, false, null, false)))
                .isEqualTo(IndexDisplayStatus.PUBLISHED);
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(1, null,
                IndexBuildState.NEW, IndexCatchupStatus.BEHIND, false, false, true,
                false, false, null, false)))
                .isEqualTo(IndexDisplayStatus.PENDING_MIGRATION);
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(2, 2L,
                IndexBuildState.BUILT, IndexCatchupStatus.BEHIND, false, false, true,
                false, false, null, false)))
                .isEqualTo(IndexDisplayStatus.PENDING_MIGRATION);
        assertThat(IndexVersionStatusPolicy.displayStatus(snapshot(2, 2L,
                IndexBuildState.BUILT, IndexCatchupStatus.CURRENT, true, false, true,
                false, false, null, false)))
                .isEqualTo(IndexDisplayStatus.MIGRATED);
    }
```

文件中其他断言 `CATCHING_UP / REBUILDING / PENDING_REBUILD / REBUILT` 的地方按上表语义改为新枚举。

- [ ] **Step 2: 写动作推导失败测试**

`src/test/java/com/kwiki/indexing/version/SearchIndexAdminActionsTest.java`：

```java
package com.kwiki.indexing.version;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SearchIndexAdminActionsTest {

    private final EditableIndexConfig config =
            new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 3);
    private final String hash = new ChunkMappingBuilder().mappingHash(1024, 3);

    private SearchIndexAdminQueryService.Actions actions(SearchIndexVersion version, boolean activeRun,
                                                         boolean scoped) {
        return SearchIndexAdminQueryService.actionsFor(version,
                version.toSnapshot(activeRun, false), activeRun, scoped, false, true);
    }

    @Test
    void 待迁移且写入关闭_只能开启写入_迁移按钮提示先开启写入() {
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        var result = actions(v2, false, false);
        assertThat(result.allowed()).containsEntry("writeToggle", true)
                .containsEntry("migrate", false).containsEntry("edit", true);
        assertThat(result.migrateBlockedReason()).isEqualTo("请先开启写入");
    }

    @Test
    void 待迁移且写入开启_可以开始存量迁移() {
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        v2.startWriteSession(42L);
        var result = actions(v2, false, false);
        assertThat(result.allowed()).containsEntry("migrate", true)
                .containsEntry("edit", false).containsEntry("select", false);
        assertThat(result.migrateBlockedReason()).isNull();
    }

    @Test
    void 已发布版本不能关闭写入也不能迁移() {
        SearchIndexVersion v1 = SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", config, hash);
        var result = actions(v1, false, false);
        assertThat(result.allowed()).containsEntry("writeToggle", false)
                .containsEntry("migrate", false).containsEntry("select", false);
    }

    @Test
    void 迁移中不能切换写入() {
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        v2.startWriteSession(42L);
        assertThat(actions(v2, true, false).allowed()).containsEntry("writeToggle", false)
                .containsEntry("migrate", false);
    }

    @Test
    void 灰度版本在全局页没有生命周期操作() {
        SearchIndexVersion v2 = new SearchIndexVersion(2, "kwiki-chunks-v2", config, hash);
        var result = actions(v2, false, true);
        assertThat(result.allowed()).containsEntry("writeToggle", false)
                .containsEntry("migrate", false).containsEntry("edit", false);
        assertThat(result.migrateBlockedReason()).isNull();
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./mvnw -q test -Dtest='IndexVersionStatusPolicyTest,SearchIndexAdminActionsTest'`
Expected: 编译失败（枚举常量、`actionsFor`、`Actions` 不存在）。

- [ ] **Step 4: 展示状态**

`IndexDisplayStatus.java` 替换为：

```java
package com.kwiki.indexing.version;

/**
 * 管理端主展示状态（后端派生，优先级从高到低）：
 * NEEDS_ATTENTION（元数据/别名事实不一致，置顶且禁用危险操作）→
 * MIGRATING（存量迁移运行中）→ PUBLISHED（当前唯一别名目标）→
 * PENDING_MIGRATION（待迁移：未构建、配置已改或未追平）→ MIGRATED（已迁移）。
 */
public enum IndexDisplayStatus {
    NEEDS_ATTENTION,
    MIGRATING,
    PUBLISHED,
    PENDING_MIGRATION,
    MIGRATED
}
```

`IndexVersionStatusPolicy.displayStatus` 替换为：

```java
    /** 管理端主展示状态；多个内部状态绝不拼成含混标签。 */
    public static IndexDisplayStatus displayStatus(IndexVersionSnapshot snapshot) {
        if (snapshot.needsAttentionReason() != null) {
            return IndexDisplayStatus.NEEDS_ATTENTION;
        }
        if (snapshot.activeRun()) {
            return IndexDisplayStatus.MIGRATING;
        }
        if (snapshot.selected()) {
            return IndexDisplayStatus.PUBLISHED;
        }
        if (snapshot.dirty() || snapshot.buildState() != IndexBuildState.BUILT
                || snapshot.catchupStatus() != IndexCatchupStatus.CURRENT) {
            return IndexDisplayStatus.PENDING_MIGRATION;
        }
        return IndexDisplayStatus.MIGRATED;
    }
```

- [ ] **Step 5: 查询服务动作推导**

`SearchIndexAdminQueryService`：

1. 在 `view(...)` 中，从 `Map<String,Boolean> actions=new LinkedHashMap<>();` 到 `if (scoped) {...}` 块结束的整段，替换为：

```java
        boolean multimodalBlocked = multimodalReadiness.isMultimodal(
                version.editableConfig().parserVersion()) && !multimodalReadiness.ready();
        // 灰度版本只服务范围内知识库，生命周期由灰度发布管理
        boolean scoped = kbScope != null && kbScope.isScoped(version.getVersionNumber());
        Actions actions = actionsFor(version, snapshot, activeRun || preparing, scoped,
                multimodalBlocked, cleanupCandidate);
```

并把 `new VersionView(...)` 的实参 `Map.copyOf(actions)` 改为 `actions.allowed()`，末尾追加 `version.getWriteEnabledEventId(), actions.migrateBlockedReason()`。

2. 新增静态推导方法与记录：

```java
    /** 全局页可执行动作：写入由管理员开关控制，迁移只能在写入开启后发起。 */
    static Actions actionsFor(SearchIndexVersion version, IndexVersionSnapshot snapshot,
                              boolean activeRun, boolean scoped, boolean multimodalBlocked,
                              boolean cleanupCandidate) {
        boolean writeEnabled = version.isWriteEnabled();
        boolean migrated = !snapshot.dirty() && snapshot.buildState() == IndexBuildState.BUILT
                && snapshot.catchupStatus() == IndexCatchupStatus.CURRENT;
        boolean idle = !activeRun;
        Map<String,Boolean> allowed = new LinkedHashMap<>();
        allowed.put("edit", !scoped && snapshot.editable());
        allowed.put("writeToggle", !scoped && idle && (writeEnabled
                ? !version.isSelected() : version.isPipelineSupported()));
        allowed.put("migrate", !scoped && idle && writeEnabled && !migrated
                && version.isPipelineSupported());
        allowed.put("validate", !scoped && idle && writeEnabled && migrated);
        allowed.put("select", !scoped && idle && !version.isSelected() && writeEnabled
                && migrated && !multimodalBlocked);
        allowed.put("delete", cleanupCandidate && idle && !writeEnabled);
        String blocked = !scoped && idle && !writeEnabled && !migrated ? "请先开启写入" : null;
        return new Actions(Map.copyOf(allowed), blocked);
    }

    record Actions(Map<String,Boolean> allowed, String migrateBlockedReason) {}
```

3. `VersionView` 记录末尾追加两个组件：`Long writeEnabledEventId,String migrateBlockedReason`。

（`preparing` 变量在 Task 6 删除，此处暂作为活动判断的一部分传入。）

- [ ] **Step 6: 控制器接口**

`SearchIndexAdminController`：

1. 字段与构造器：删除 `ManualIndexRebuildService rebuilds`、`SwitchPreparationService preparations`、`IndexVersionEnablementService enablement`，新增 `IndexVersionWriteService writes`、`IndexMigrationService migrations`，构造参数相应替换。
2. 删除 `rebuild`、`prepare`、`disable`、`reenable` 四个方法。
3. 新增：

```java
    @PutMapping("/versions/{version}/write")
    public TransDTO<Map<String,Object>> write(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody WriteRequest request){
        requireGlobalVersion(version);
        boolean enabled=Boolean.TRUE.equals(request.enabled());
        return TransDTO.success(command(key,enabled?"WRITE_ENABLE":"WRITE_DISABLE",version,user,
                ()->version(enabled?writes.enable(version):writes.disable(version))));
    }

    @PostMapping("/versions/{version}/migrate")
    public ResponseEntity<TransDTO<Map<String,Object>>> migrate(@AuthenticationPrincipal CurrentUser user,
            @PathVariable int version,@RequestHeader("Idempotency-Key") String key){
        requireGlobalVersion(version);
        Map<String,Object> body=command(key,"MIGRATE",version,user,()->{
            var result=migrations.migrate(version,user.username());
            return map("accepted",result.accepted(),"runId",result.runId(),"resumed",result.resumed(),"code",result.code());});
        HttpStatus status=Boolean.FALSE.equals(body.get("accepted"))?HttpStatus.CONFLICT:HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(TransDTO.success(body));
    }
```

4. `version(SearchIndexVersion)` 的 map 末尾追加 `"writeEnabledEventId",value.getWriteEnabledEventId()`。
5. 新增请求记录（文件末尾，`DeleteRequest` 旁），并 `import jakarta.validation.constraints.NotNull;`：

```java
    public record WriteRequest(@NotNull Boolean enabled){}
```

6. `requireGlobalVersion` 的注释改为「灰度版本只能由灰度发布页驱动：全局接口的编辑、写入开关与存量迁移一律拒绝。」

- [ ] **Step 7: 删除服务前置条件与日志文案**

`SearchIndexDeletionService.requireEligible`：删除这一行（写入开关关闭即满足删除前置，新版本从未开启写入时 `adminDisabled` 为 false）：

```java
        if (!version.isAdminDisabled()) throw new IllegalStateException("version must be explicitly disabled before deletion");
```

`SearchIndexAdminService`：两处日志文案中的 `pending rebuild` 改为 `pending migration`；`editVersion` 注释中的「待重建」改为「待迁移」。

- [ ] **Step 8: 修正受影响的控制器/删除测试**

- `SearchIndexAdminControllerSecurityTest`、`SearchIndexAdminControllerGrayGuardTest`：把构造/`@MockBean` 中的 `ManualIndexRebuildService`、`SwitchPreparationService`、`IndexVersionEnablementService` 换成 `IndexVersionWriteService`、`IndexMigrationService`；对 `/rebuild`、`/prepare`、`/disable`、`/reenable` 的请求分别替换为 `POST /versions/{n}/migrate`、`POST /versions/{n}/migrate`、`PUT /versions/{n}/write` + body `{"enabled":false}`、`PUT /versions/{n}/write` + body `{"enabled":true}`（去重后保留覆盖到的断言：非 ADMIN 被拒、灰度版本返回冲突）。
- `SearchIndexDeletionServiceTest`：删除「未显式停用时拒绝删除」的用例（若存在）；其余不变。

- [ ] **Step 9: 运行测试确认通过**

Run: `./mvnw -q test -Dtest='IndexVersionStatusPolicyTest,SearchIndexAdminActionsTest,SearchIndexAdminControllerSecurityTest,SearchIndexAdminControllerGrayGuardTest,SearchIndexDeletionServiceTest,SearchIndexAdminServiceTest'`
Expected: 全部 PASS。

- [ ] **Step 10: 提交**

```bash
git add src/main/java/com/kwiki src/test/java/com/kwiki
git commit -m "feat(admin-api): 全局索引新增写入开关与存量迁移接口，状态改为待迁移/迁移中/已迁移"
```

---

### Task 6: 删除旧的重建 / 切换准备 / 补齐代码

**Files:**
- Delete: `indexing/version/SwitchPreparationService.java`、`SwitchPreparationBarrierService.java`、`SwitchCatchupProcessor.java`、`SwitchCatchupScheduler.java`、`IndexVersionEnablementService.java`、`ManualIndexRebuildService.java`
- Delete tests: `SwitchPreparationServiceTest.java`、`SwitchPreparationBarrierServiceTest.java`、`IndexVersionEnablementServiceTest.java`、`ManualIndexRebuildServiceTest.java`，以及只测试已删类的其他测试（如 `SwitchCatchupProcessorTest`，若存在）
- Modify: `SearchIndexVersion.java`、`SearchIndexRebuildRun.java`、`IndexVersionSnapshot.java`、`IndexVersionStatusPolicy.java`、`SearchIndexAdminQueryService.java`、`SearchIndexDeletionService.java`、`RebuildTargetEnqueuer.java`、`SearchIndexRebuildRunRepository.java`，以及所有 `toSnapshot(…, …)` / `new IndexVersionSnapshot(...)` 调用点
- Test: `SearchIndexRebuildRunWatermarkTest.java`、`IndexVersionStatusPolicyTest.java`

**Interfaces:**
- Produces: `IndexVersionSnapshot` 去掉 `switchPreparing` 组件（12 个组件）；`SearchIndexVersion#toSnapshot(boolean activeRun)`。

- [ ] **Step 1: 确认无残留引用后删除类**

Run: `grep -rn "SwitchPreparationService\|SwitchPreparationBarrierService\|SwitchCatchupProcessor\|SwitchCatchupScheduler\|IndexVersionEnablementService\|ManualIndexRebuildService" src/main src/test`
Expected: 只命中这些类自身及其专属测试。若命中其他文件（例如启动引导中调用 `VersionRebuildCoordinator.startInitial` 的地方引用了 `ManualIndexRebuildService`），先把该引用改为 `IndexMigrationService`/`IndexVersionWriteService` 的等价调用再继续。

然后删除上面列出的 6 个主类与 4 个专属测试文件（`git rm`）。

- [ ] **Step 2: 清理实体与 run**

- `SearchIndexVersion`：删除 `enableForSwitchPreparation()`、`disableByAdministrator()`、`reenableByAdministrator()`；`toSnapshot(boolean activeRun, boolean switchPreparing)` 改为 `toSnapshot(boolean activeRun)`，构造快照时去掉最后一个参数。
- `SearchIndexRebuildRun`：删除 `startSwitchPreparation`、`advanceReplayCursor`、`replayComplete`、`captureCatchupBarrier`、`markSwitchReady`。保留字段与 getter（`RunView` 仍展示历史值）。
- `RebuildTargetEnqueuer`：删除 `enqueueTailPage`、`enqueueTailAttachment`、`replayEvent` 与 `ChangeEvent` 记录（仅被已删类使用）。
- `SearchIndexRebuildRunRepository`：删除 `findByStateAndSwitchStateOrderByIdAsc`、`existsByVersionNumberAndSwitchState`（先 grep 确认无其他调用）。

- [ ] **Step 3: 清理快照与策略**

- `IndexVersionSnapshot`：删除组件 `switchPreparing` 及其 javadoc `@param`；`editable()` 改为 `return !selected && !writeEnabled && !activeRun && !deleted;`；`withNeedsAttention` 去掉该实参。
- `IndexVersionStatusPolicy`：删除 `enableWrites`、`disableWrites`、`caughtUp`（均已无调用）；其余方法中所有 `new IndexVersionSnapshot(..., snapshot.switchPreparing())` / `..., false, false)` 去掉最后一个实参；`publish` 末尾改为 `..., null, false)`。
- 全局替换调用点：`toSnapshot(x, y)` → `toSnapshot(x)`（`JpaRebuildRunRegistry`、`SearchIndexSelectionRegistry`、`SearchIndexAdminService`、`SearchIndexAdminQueryService`、`IndexMigrationService`、`IndexPipelineSupportReconciler`、`AliasReconciliationService` 等，以编译错误为准）。
- `SearchIndexAdminQueryService.view`：删除 `preparing` 变量，`actionsFor(..., activeRun, ...)`；`toSnapshot(activeRun)`。
- `SearchIndexDeletionService.requireEligible`：删除 `|| runs.existsByVersionNumberAndSwitchState(...)` 条件，报错文案改为 `"version has an active migration"`。
- 类注释中提到「切换准备 / 补齐」的地方改为「存量迁移」（`IndexVersionStatusPolicy` 类注释、`IndexCatchupStatus` 注释改为：`CURRENT 表示写入会话内的存量迁移已完成、双写持续追平；BEHIND 表示尚未迁移或写入已关闭。`）。

- [ ] **Step 4: 修正测试**

- `IndexVersionStatusPolicyTest`：`snapshot(...)` 工厂删除 `switchPreparing` 形参与实参；删除测试 `enableWrites/disableWrites/caughtUp` 的用例。
- `SearchIndexRebuildRunWatermarkTest`：删除针对已删方法（双写水位、重放游标、屏障）的用例；若文件因此为空则 `git rm`。
- 其余测试中 `toSnapshot(a, b)` → `toSnapshot(a)`，`new IndexVersionSnapshot(...13 个实参)` 去掉最后一个。

- [ ] **Step 5: 全量编译与测试**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS，0 失败。含 `NoDockerMiddlewareAuditTest`、`RepositorySecretsAuditTest`。

- [ ] **Step 6: 提交**

```bash
git add -A src/main/java src/test/java
git commit -m "refactor(indexing): 删除旧的重建、切换准备与补齐流程"
```

---

### Task 7: 管理前端——写入列、迁移按钮与文案

**Files:**
- Modify: `admin-frontend/src/api.ts`
- Modify: `admin-frontend/src/status.ts`
- Modify: `admin-frontend/src/views/IndexManagementView.vue`
- Modify: `admin-frontend/src/views/GrayReleaseView.vue`、`admin-frontend/src/components/GrayReleaseCard.vue`、`admin-frontend/src/components/CurrentIndexCards.vue`（仅文案）

**Interfaces:**
- Consumes: Task 5 的 HTTP 接口与 `VersionView` 字段。

- [ ] **Step 1: 类型**

`api.ts` 中 `Version` 接口：`displayStatus` 联合类型改为 `"PENDING_MIGRATION"|"MIGRATING"|"MIGRATED"|"PUBLISHED"|"NEEDS_ATTENTION"`，并追加字段 `writeEnabledEventId:number|null;migrateBlockedReason:string|null`。

- [ ] **Step 2: 状态文案**

`status.ts` 替换为：

```ts
import type{Version}from"./api";
export const statusLabel:Record<Version["displayStatus"],string>={PENDING_MIGRATION:"待迁移",MIGRATING:"迁移中",MIGRATED:"已迁移",PUBLISHED:"已发布",NEEDS_ATTENTION:"需要处理"};
export const statusType=(status:Version["displayStatus"]):"success"|"warning"|"danger"|"info"=>status==="PUBLISHED"?"success":status==="NEEDS_ATTENTION"?"danger":status==="MIGRATING"?"warning":"info";
```

- [ ] **Step 3: 视图脚本**

`IndexManagementView.vue` `<script setup>`：

1. `activeRuns` 改为：

```ts
const activeRuns = computed(() => runs.value.filter(r => ["RUNNING", "PAUSED"].includes(r.state)));
```

2. `save()` 成功提示中的「配置已保存，版本变为待重建」改为「配置已保存，版本变为待迁移」。

3. `act(...)` 整体替换为：

```ts
async function act(target: Version, action: string) {
  try {
    if (action === "migrate") {
      const result = await api.command<{ accepted: boolean; runId?: number; code: string }>(`/versions/${target.versionNumber}/migrate`);
      if (!result.accepted) throw new ApiError(409, `版本忙碌，当前 runId=${result.runId ?? "未知"}`);
      if (result.runId) {
        selectedRun.value = result.runId;
        localStorage.setItem("kwiki_admin_run", String(result.runId));
      }
    } else if (action === "validate") {
      await api.command(`/versions/${target.versionNumber}/validate`);
    }
    ElMessage.success("操作已提交");
    pollDelay = 1000;
    await load();
  } catch (error) {
    showError(error);
  }
}

async function toggleWrite(target: Version, enabled: boolean) {
  try {
    await ElMessageBox.confirm(
      enabled
        ? "开启后将清空该版本的物理索引并开始双写，之后需执行「开始存量迁移」补入历史数据。"
        : "关闭后该版本不再接收新内容；重新开启需清空并重新全量迁移。",
      enabled ? "开启写入" : "关闭写入", { type: "warning" });
    await api.command(`/versions/${target.versionNumber}/write`, "PUT", { enabled });
    ElMessage.success(enabled ? "写入已开启" : "写入已关闭");
    pollDelay = 1000;
    await load();
  } catch (error) {
    if (error !== "cancel") showError(error);
  }
}

const writeToggleHint = (row: Version) =>
  row.kbScoped ? "灰度版本的写入由灰度发布管理"
    : row.selected ? "已发布版本不能关闭写入"
    : row.displayStatus === "MIGRATING" ? "迁移进行中"
    : "";
```

- [ ] **Step 4: 视图模板**

1. 顶部说明 `<p class="muted">` 中「这里负责它的创建、重建、补齐与热切换。」改为「这里负责它的创建、写入开关、存量迁移与热切换。」
2. 解析器说明 `description` 中「切换的是已重建、补齐且通过校验的索引版本」改为「切换的是已开启写入、完成存量迁移且通过校验的索引版本」；多模态告警中「再重建、补齐和校验 v2 索引」改为「再开启写入、存量迁移并校验 v2 索引」。
3. 「进行中任务」卡片 hint 改为「正在迁移的 run」；「双写待处理」hint 保持。
4. 状态列：删除 `<div>{{ row.writeEnabled ? "写入启用" : "写入停用" }}</div>`，并在状态列之后插入新列：

```vue
              <el-table-column label="写入" width="110">
                <template #default="{ row }">
                  <el-tooltip :content="writeToggleHint(row)" :disabled="!writeToggleHint(row)">
                    <el-switch
                      :model-value="row.writeEnabled"
                      :disabled="!row.allowedActions.writeToggle"
                      @change="(value: string | number | boolean) => toggleWrite(row, Boolean(value))"/>
                  </el-tooltip>
                </template>
              </el-table-column>
```

5. 操作列 `<div class="actions">` 内容替换为：

```vue
                    <el-button v-if="row.allowedActions.edit" @click="edit(row)">编辑</el-button>
                    <el-button v-if="row.allowedActions.migrate" type="primary" @click="act(row, 'migrate')">开始存量迁移</el-button>
                    <el-tooltip v-else-if="row.migrateBlockedReason" :content="row.migrateBlockedReason">
                      <span><el-button type="primary" disabled>开始存量迁移</el-button></span>
                    </el-tooltip>
                    <el-button v-if="row.allowedActions.validate" @click="act(row, 'validate')">校验</el-button>
                    <el-button v-if="row.allowedActions.select" type="success" @click="openSelect(row)">选择版本</el-button>
                    <el-button v-if="row.allowedActions.delete" type="danger" @click="openDelete(row)">清理</el-button>
```

6. Tab「重建与补齐」：`label` 改为「迁移记录」；说明改为「存量迁移 = 写入开启后，按版本解析器从原始文档重建双写起点之前的历史内容；之后的变更由双写负责。」；描述项「状态」改为 `{{ currentRun.kind }} / {{ currentRun.state }}`；「事件游标」项改为 `label="双写起点"`，内容 `{{ currentRun.buildStartEventId }}`；删除「补齐范围」列。
7. Tab「双写统计」说明改为「各版本实时双写任务的执行情况；「待处理」归零且迁移完成后才能通过校验。」

- [ ] **Step 5: 灰度与卡片文案**

Run: `grep -n "重建\|补齐\|PREPARING\|switchState" admin-frontend/src/views/GrayReleaseView.vue admin-frontend/src/components/GrayReleaseCard.vue admin-frontend/src/components/CurrentIndexCards.vue`

按下表逐处替换展示文案（不改变 API 字段名）：

| 原文 | 新文 |
|---|---|
| 重建 → 补齐 → 校验 | 开启双写 → 存量迁移 → 校验 |
| 重建中 / 正在重建 | 存量迁移中 |
| 补齐中 / 正在补齐 | 存量迁移中 |
| 重建 / 补齐（泛指同步过程） | 存量迁移 |

`switchState` 相关的进度展示若基于 `PREPARING/READY` 判断阶段，改为仅基于 `progress.runState`（`RUNNING/PAUSED` → 存量迁移中，`COMPLETED` → 迁移完成，待校验）。

- [ ] **Step 6: 构建**

Run: `cd admin-frontend && npm run build`
Expected: vue-tsc 类型检查与 Vite 构建成功，无错误。

- [ ] **Step 7: 提交**

```bash
git add admin-frontend/src
git commit -m "feat(admin): 版本列表新增写入开关，开始补齐改为开始存量迁移"
```

---

### Task 8: 端到端验证

**Files:** 无代码改动（如发现缺陷，回到对应 Task 修复并补测试）。

- [ ] **Step 1: 全量后端测试**

Run: `./mvnw -q test`
Expected: BUILD SUCCESS。

- [ ] **Step 2: 启动应用并在管理页手动验证**

按项目的 run 方式启动后端与 `admin-frontend`（中间件由 `KWIKI_*` 环境变量提供，且 `kwiki.indexing.management.mutations-enabled=true`），打开 `/admin` 全局索引页，逐项确认：

1. 新建版本 v3 → 状态「待迁移」，写入开关关闭，「开始存量迁移」按钮禁用且悬停提示「请先开启写入」。
2. 打开 v3 写入开关 → 确认弹窗 → 开关变为开启，「开始存量迁移」可点。
3. 点击「开始存量迁移」→ 状态「迁移中」，「迁移记录」页显示 `MIGRATION` run 与双写起点；完成后状态「已迁移」，出现「校验」「选择版本」。
4. 已发布 v1 的写入开关禁用，悬停提示「已发布版本不能关闭写入」；v1 不再显示「开始补齐」。
5. 关闭 v3 写入 → 状态回到「待迁移」。
6. 灰度页创建灰度并「开始同步」→ 审计中先出现写入开启，再出现迁移 run；完成后自动校验并进入「已同步」。
7. 「审计」页出现 `WRITE_ENABLE`、`MIGRATE`、`WRITE_DISABLE` 记录。

- [ ] **Step 3: 记录验证结果**

在 spec 文件末尾追加「## 9. 验证记录」一节，逐条写明上述 7 项的结果（通过/失败及现象），然后提交：

```bash
git add docs/superpowers/specs/2026-09-30-index-migration-write-toggle-design.md
git commit -m "docs(indexing): 记录存量迁移与写入开关的验证结果"
```
