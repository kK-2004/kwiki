# 索引灰度发布 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按 `docs/superpowers/specs/2026-09-27-index-gray-release-design.md`，实现「按知识库灰度新解析器」：灰度页选知识库 + 解析器 → 自动建限定范围的索引版本 → 一键同步（重建 → 补齐 → 校验）→ 手动切换/切回 → 结束；并在全局索引页顶部一眼展示并切换当前别名与解析器。

**Architecture:** 新增 `com.kwiki.indexing.gray` 包：知识库范围表 `search_index_version_kb_scope` 让重建/补齐/校验/写入只处理范围内知识库；灰度状态存 `index_gray_release(_kb)`；同步驱动器按现有重建 → 补齐 → 校验链路自动推进；读路由在一次 ES 查询里同时覆盖全局别名与已切换灰度的物理索引并附加路由过滤；导入按知识库选择解析器。管理端新增「灰度发布」页，全局页顶部加两张可切换的卡片。

**Tech Stack:** Spring Boot 3 / Java 21、Spring JDBC + JPA、Flyway（MySQL）、Elasticsearch Java client、JUnit 5 + Mockito + AssertJ；Vue 3 + Element Plus + Vitest（admin-frontend）。

## Global Constraints

- **前置条件：** 另一会话对 `IndexingWorker`、`VersionedIndexingPipelineRegistry`、`AliasSwitchService`、`MultimodalSwitchReadiness`、`WikiImportDocumentParser`、`IndexManagementView.vue`、`admin-frontend/src/api.ts` 的改动**提交后**再开始 Task 1。开工前运行 `git status --short`，上述文件不得处于未提交状态。
- 注释一律简体中文（`AGENT.md` 第 1 节）；**不得修改** `src/main/resources/db/migration/` 下已有文件；新迁移为 `V33__index_gray_release.sql`（`V32` 已被暂存的 MCP 改动占用；开工前 `ls src/main/resources/db/migration | sort -V | tail -1` 确认最新号 < 33）。
- 不引入 Docker / Testcontainers；需要 MySQL 的契约测试沿用 `@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")` 门控。
- 提交信息中文 `type(scope): 描述`，**不加 Co-Authored-By / Claude 署名**。
- 解析器显示名：`kwiki-parse-1` → `tika-v1`，`kwiki-parse-2` → `pdfbox-v2`；接口与存储仍使用原标识。
- 灰度状态取值固定为 `CREATED` / `SYNCING` / `SYNCED` / `SWITCHED` / `ENDED`。
- 一个知识库同时只能属于一个未结束的灰度；灰度版本**不得**被全局「选择版本」。
- 旧版本永不自动删除；结束灰度只停用灰度版本写入，不删除索引。
- 新增构造依赖时，保留原构造器（委托新构造器并传 `null`），新构造器标 `@Autowired`——与 `ManualIndexRebuildService` 的写法一致，避免破坏现有测试。
- 后端验证命令：`./mvnw -q test -Dtest=<测试类>`；全量：`./mvnw -q test`（外部契约测试在无环境变量时自动跳过）。
- 管理端验证命令：`cd admin-frontend && npx vue-tsc --noEmit && npx vitest run && npx vite build`。

---

## 文件结构

| 文件 | 职责 |
|---|---|
| `src/main/resources/db/migration/V33__index_gray_release.sql` | 范围表 + 灰度表 |
| `indexing/gray/IndexVersionKbScope.java` | 版本知识库范围：登记、查询、SQL 过滤片段、写入判定 |
| `indexing/gray/GrayReleaseStatus.java` | 状态枚举 |
| `indexing/gray/GrayRelease.java` | 灰度只读视图 record（含知识库列表） |
| `indexing/gray/GrayReleaseStore.java` | 存储接口 |
| `indexing/gray/JdbcGrayReleaseStore.java` | 存储 JDBC 实现 |
| `indexing/gray/ParserCatalog.java` | 可用解析器、显示名、可用性 |
| `indexing/gray/GrayReleaseService.java` | 状态机：创建/同步/推进/切换/切回/结束 |
| `indexing/gray/GrayReleaseSyncDriver.java` | 定时推进 `SYNCING` 灰度 |
| `indexing/gray/ReadRouting.java` | 读路由快照（领域对象，无 ES 类型） |
| `indexing/gray/GrayReadRoutes.java` | 读路由与知识库解析器查询 |
| `infrastructure/elasticsearch/EsReadRouting.java` | 读路由 → ES 索引列表 + 过滤 Query |
| `wiki/api/SearchIndexGrayReleaseController.java` | 灰度管理接口 |
| 修改：`FixedRangeRebuildScanner`、`SwitchCatchupProcessor`、`SearchIndexValidationService` | 资源查询附加范围过滤 |
| 修改：`IndexingWorker` | upsert 前范围判定 |
| 修改：`SearchIndexAdminQueryService`、`AliasSwitchService` | 灰度版本禁止全局选择 |
| 修改：`VectorRecallAdapter`、`Bm25RecallAdapter`、`EsChunkLookup` | 读路由 |
| 修改：`WikiImportDocumentParser`、`WikiImportService` | 按知识库选解析器 |
| 修改：`SearchIndexAdminExceptionAdvice`、`SearchIndexRebuildRunRepository` | 覆盖新控制器；最新 run 查询 |
| `admin-frontend/src/parsers.ts` | 解析器显示名 |
| `admin-frontend/src/api.ts` | 灰度类型与接口 |
| `admin-frontend/src/views/GrayReleaseView.vue` | 灰度发布页 |
| `admin-frontend/src/components/GrayReleaseCard.vue` | 单个灰度卡片 |
| `admin-frontend/src/components/GrayReleaseCreateDialog.vue` | 新建灰度弹窗 |
| `admin-frontend/src/components/CurrentIndexCards.vue` | 全局页顶部两张卡片 + 切换弹窗 |
| 修改：`admin-frontend/src/router.ts`、`components/AdminLayout.vue`、`views/IndexManagementView.vue` | 路由、导航、挂载卡片 |

（Java 路径均以 `src/main/java/com/kwiki/` 为前缀，测试以 `src/test/java/com/kwiki/` 为前缀。）

---

### Task 1：迁移与版本知识库范围

**Files:**
- Create: `src/main/resources/db/migration/V33__index_gray_release.sql`
- Create: `src/main/java/com/kwiki/indexing/gray/IndexVersionKbScope.java`
- Test: `src/test/java/com/kwiki/indexing/gray/IndexVersionKbScopeTest.java`
- Test: `src/test/java/com/kwiki/indexing/gray/GrayReleaseMigrationContractTest.java`

**Interfaces:**
- Produces:
  - `IndexVersionKbScope#register(int versionNumber, Collection<Long> kbIds)`
  - `IndexVersionKbScope#kbIds(int versionNumber): Set<Long>`（空集 = 无范围，即全局版本）
  - `IndexVersionKbScope#isScoped(int versionNumber): boolean`
  - `IndexVersionKbScope#accepts(int versionNumber, long kbId): boolean`（无范围恒为 true）
  - `static String IndexVersionKbScope.sqlFilter(String kbColumn)`：返回以 ` AND (` 开头的 SQL 片段，**需要按顺序追加两个参数：versionNumber, versionNumber**。

- [ ] **Step 1：写迁移 `V33__index_gray_release.sql`**

```sql
-- V33：索引灰度发布。
-- search_index_version_kb_scope：为索引版本登记知识库范围；没有任何行的版本即全局版本。
-- index_gray_release / index_gray_release_kb：灰度发布及其知识库；
-- active_kb_id 仅在灰度未结束时等于 kb_id，借唯一索引保证一个知识库同时只在一个进行中的灰度里。

CREATE TABLE search_index_version_kb_scope (
    version_number INT         NOT NULL,
    kb_id          BIGINT      NOT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (version_number, kb_id),
    KEY idx_search_index_version_kb_scope_kb (kb_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE index_gray_release (
    id                   BIGINT        NOT NULL AUTO_INCREMENT,
    name                 VARCHAR(120)  NOT NULL,
    parser_version       VARCHAR(64)   NOT NULL,
    index_version_number INT           NOT NULL,
    status               VARCHAR(20)   NOT NULL,
    last_error           VARCHAR(1000) NULL,
    created_by           VARCHAR(100)  NOT NULL,
    created_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    switched_at          DATETIME(6)   NULL,
    ended_at             DATETIME(6)   NULL,
    updated_at           DATETIME(6)   NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_index_gray_release_version (index_version_number),
    KEY idx_index_gray_release_status (status),
    CONSTRAINT ck_index_gray_release_status
        CHECK (status IN ('CREATED', 'SYNCING', 'SYNCED', 'SWITCHED', 'ENDED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE index_gray_release_kb (
    release_id   BIGINT NOT NULL,
    kb_id        BIGINT NOT NULL,
    active_kb_id BIGINT NULL,
    PRIMARY KEY (release_id, kb_id),
    UNIQUE KEY uk_index_gray_release_active_kb (active_kb_id),
    CONSTRAINT fk_index_gray_release_kb_release
        FOREIGN KEY (release_id) REFERENCES index_gray_release (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- [ ] **Step 2：写失败的单测 `IndexVersionKbScopeTest`**

```java
package com.kwiki.indexing.gray;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexVersionKbScopeTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final IndexVersionKbScope scope = new IndexVersionKbScope(jdbc);

    @Test
    void 无范围行的版本视为全局版本_接受任何知识库() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(3))).thenReturn(List.of());
        assertThat(scope.isScoped(3)).isFalse();
        assertThat(scope.accepts(3, 42L)).isTrue();
    }

    @Test
    void 有范围的版本只接受范围内知识库() {
        when(jdbc.queryForList(anyString(), eq(Long.class), eq(4))).thenReturn(List.of(7L, 9L));
        assertThat(scope.kbIds(4)).isEqualTo(Set.of(7L, 9L));
        assertThat(scope.isScoped(4)).isTrue();
        assertThat(scope.accepts(4, 7L)).isTrue();
        assertThat(scope.accepts(4, 8L)).isFalse();
    }

    @Test
    void 登记范围逐个插入知识库() {
        scope.register(5, List.of(1L, 2L));
        verify(jdbc).batchUpdate(eq("INSERT INTO search_index_version_kb_scope (version_number, kb_id) VALUES (?, ?)"),
                any(List.class));
    }

    @Test
    void SQL过滤片段对全局版本不生效_对范围版本限定知识库() {
        String fragment = IndexVersionKbScope.sqlFilter("p.kb_id");
        assertThat(fragment).startsWith(" AND (")
                .contains("NOT EXISTS (SELECT 1 FROM search_index_version_kb_scope s0 WHERE s0.version_number = ?)")
                .contains("p.kb_id IN (SELECT s1.kb_id FROM search_index_version_kb_scope s1 WHERE s1.version_number = ?)");
    }
}
```

- [ ] **Step 3：运行确认失败**

Run：`./mvnw -q test -Dtest=IndexVersionKbScopeTest`
Expected：编译失败，找不到 `IndexVersionKbScope`。

- [ ] **Step 4：实现 `IndexVersionKbScope`**

```java
package com.kwiki.indexing.gray;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 索引版本的知识库范围。没有任何范围行的版本是全局版本；
 * 灰度版本登记其知识库集合，重建、补齐、校验与写入都只处理范围内的知识库。
 */
@Component
public class IndexVersionKbScope {

    private static final String SELECT_KB_IDS =
            "SELECT kb_id FROM search_index_version_kb_scope WHERE version_number = ?";
    private static final String INSERT =
            "INSERT INTO search_index_version_kb_scope (version_number, kb_id) VALUES (?, ?)";

    private final JdbcTemplate jdbc;

    @Autowired
    public IndexVersionKbScope(ObjectProvider<JdbcTemplate> jdbc) {
        this(jdbc.getIfAvailable());
    }

    IndexVersionKbScope(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 资源查询的范围过滤片段：全局版本不生效，范围版本限定知识库列。
     * 调用方必须按顺序追加两个参数：versionNumber, versionNumber。
     */
    public static String sqlFilter(String kbColumn) {
        return " AND (NOT EXISTS (SELECT 1 FROM search_index_version_kb_scope s0 WHERE s0.version_number = ?)"
                + " OR " + kbColumn
                + " IN (SELECT s1.kb_id FROM search_index_version_kb_scope s1 WHERE s1.version_number = ?))";
    }

    public void register(int versionNumber, Collection<Long> kbIds) {
        List<Object[]> rows = kbIds.stream().distinct()
                .map(kbId -> new Object[] {versionNumber, kbId}).toList();
        requireJdbc().batchUpdate(INSERT, rows);
    }

    public Set<Long> kbIds(int versionNumber) {
        if (jdbc == null) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.queryForList(SELECT_KB_IDS, Long.class, versionNumber));
    }

    public boolean isScoped(int versionNumber) {
        return !kbIds(versionNumber).isEmpty();
    }

    public boolean accepts(int versionNumber, long kbId) {
        Set<Long> scope = kbIds(versionNumber);
        return scope.isEmpty() || scope.contains(kbId);
    }

    private JdbcTemplate requireJdbc() {
        if (jdbc == null) {
            throw new IllegalStateException("search index version scope registry is unavailable");
        }
        return jdbc;
    }
}
```

- [ ] **Step 5：运行确认通过**

Run：`./mvnw -q test -Dtest=IndexVersionKbScopeTest`
Expected：4 tests pass。

- [ ] **Step 6：迁移契约测试 `GrayReleaseMigrationContractTest`**（沿用 `SearchIndexMigrationContractTest` 的门控与建连方式）

```java
package com.kwiki.indexing.gray;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V33 契约：同一知识库不能同时处于两个未结束的灰度；结束后可再次加入。需要 KWIKI_IT_MYSQL_URL。 */
@EnabledIfEnvironmentVariable(named = "KWIKI_IT_MYSQL_URL", matches = ".+")
class GrayReleaseMigrationContractTest {

    private static final String URL = System.getenv().getOrDefault("KWIKI_IT_MYSQL_URL", "");

    @BeforeAll
    static void migrate() {
        String user = System.getenv().getOrDefault("KWIKI_IT_MYSQL_USERNAME", "");
        String password = System.getenv().getOrDefault("KWIKI_IT_MYSQL_PASSWORD", "");
        Flyway.configure().dataSource(URL, user, password)
                .locations("classpath:db/migration").cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(URL, user, password)
                .locations("classpath:db/migration").load().migrate();
    }

    private static Connection open() throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", System.getenv().getOrDefault("KWIKI_IT_MYSQL_USERNAME", ""));
        props.setProperty("password", System.getenv().getOrDefault("KWIKI_IT_MYSQL_PASSWORD", ""));
        return DriverManager.getConnection(URL, props);
    }

    @Test
    void 活跃灰度中知识库唯一_结束后释放() throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO index_gray_release (id, name, parser_version, index_version_number, status, created_by)"
                    + " VALUES (1, 'a', 'kwiki-parse-2', 90, 'CREATED', 'admin'), (2, 'b', 'kwiki-parse-2', 91, 'CREATED', 'admin')");
            statement.executeUpdate("INSERT INTO index_gray_release_kb (release_id, kb_id, active_kb_id) VALUES (1, 7, 7)");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO index_gray_release_kb (release_id, kb_id, active_kb_id) VALUES (2, 7, 7)"))
                    .isInstanceOf(SQLException.class);
            statement.executeUpdate("UPDATE index_gray_release_kb SET active_kb_id = NULL WHERE release_id = 1");
            statement.executeUpdate("INSERT INTO index_gray_release_kb (release_id, kb_id, active_kb_id) VALUES (2, 7, 7)");
            assertThatThrownBy(() -> statement.executeUpdate(
                    "UPDATE index_gray_release SET status = 'BOGUS' WHERE id = 1"))
                    .isInstanceOf(SQLException.class);
        }
    }
}
```

- [ ] **Step 7：迁移文件名校验 + 提交**

Run：`node tools/validate-migrations.mjs && ./mvnw -q test -Dtest=IndexVersionKbScopeTest,GrayReleaseMigrationContractTest`
Expected：`validated N migrations`；单测通过，契约测试在无 `KWIKI_IT_MYSQL_URL` 时跳过。

```bash
git add src/main/resources/db/migration/V33__index_gray_release.sql src/main/java/com/kwiki/indexing/gray/IndexVersionKbScope.java src/test/java/com/kwiki/indexing/gray/
git commit -m "feat(indexing): 新增灰度发布表与索引版本知识库范围"
```

---

### Task 2：范围感知的重建/补齐/校验/写入，并禁止全局选择灰度版本

**Files:**
- Modify: `src/main/java/com/kwiki/indexing/version/FixedRangeRebuildScanner.java`（`pageRows`、`attachmentRows`、`processBatch`）
- Modify: `src/main/java/com/kwiki/indexing/version/SwitchCatchupProcessor.java`（`tailPageRows`、`tailAttachmentRows` 及其调用处）
- Modify: `src/main/java/com/kwiki/indexing/version/SearchIndexValidationService.java`（`effectiveResources`）
- Modify: `src/main/java/com/kwiki/indexing/job/IndexingWorker.java`（`executeFencedPageUpsert`、`executeAttachmentUpsert`）
- Modify: `src/main/java/com/kwiki/indexing/version/SearchIndexAdminQueryService.java`（`view`、`VersionView`）
- Modify: `src/main/java/com/kwiki/indexing/version/AliasSwitchService.java`（`select`）
- Modify: `src/main/java/com/kwiki/indexing/version/IndexVersionRetentionAdvisor.java`（灰度版本不占保留名额）
- Test: `src/test/java/com/kwiki/indexing/gray/ScopedIndexingTest.java`

**Interfaces:**
- Consumes：Task 1 的 `IndexVersionKbScope#sqlFilter / accepts / isScoped`。
- Produces：`VersionView` 末尾新增字段 `boolean kbScoped`；`allowedActions.select` 对灰度版本恒为 `false`。

- [ ] **Step 1：写失败的测试 `ScopedIndexingTest`**

```java
package com.kwiki.indexing.gray;

import com.kwiki.indexing.version.FixedRangeRebuildScanner;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 资源扫描 SQL 必须带版本知识库范围过滤：以源码契约方式断言，
 * 防止后续修改扫描语句时遗漏范围（行为由 IndexVersionKbScopeTest 与集成验证覆盖）。
 */
class ScopedIndexingTest {

    private static String source(String relative) throws Exception {
        return Files.readString(Path.of("src/main/java/com/kwiki/" + relative));
    }

    @Test
    void 重建扫描的页面与附件查询都带范围过滤() throws Exception {
        String scanner = source("indexing/version/FixedRangeRebuildScanner.java");
        assertThat(scanner).contains("IndexVersionKbScope.sqlFilter(\"p.kb_id\")")
                .contains("IndexVersionKbScope.sqlFilter(\"a.kb_id\")");
    }

    @Test
    void 补齐尾部扫描的页面与附件查询都带范围过滤() throws Exception {
        String catchup = source("indexing/version/SwitchCatchupProcessor.java");
        assertThat(catchup).contains("IndexVersionKbScope.sqlFilter(\"p.kb_id\")")
                .contains("IndexVersionKbScope.sqlFilter(\"a.kb_id\")");
    }

    @Test
    void 校验资源清单带范围过滤() throws Exception {
        String validation = source("indexing/version/SearchIndexValidationService.java");
        assertThat(validation).contains("IndexVersionKbScope.sqlFilter(\"p.kb_id\")")
                .contains("IndexVersionKbScope.sqlFilter(\"a.kb_id\")");
    }

    @Test
    void 写入前判断目标版本是否接受该知识库() throws Exception {
        String worker = source("indexing/job/IndexingWorker.java");
        assertThat(worker).contains("acceptsKnowledgeBase(indexVersion, page.getKbId())")
                .contains("acceptsKnowledgeBase(indexVersion, attachment.getKbId())");
    }

    @Test
    void 扫描器类仍存在() {
        assertThat(FixedRangeRebuildScanner.class).isNotNull();
    }
}
```

另在 `src/test/java/com/kwiki/indexing/version/AliasSwitchServiceTest.java` 末尾新增用例（按该文件已有的构造方式创建 `service`，再调用 `service.setKbScope(scope)`）：

```java
    @Test
    void 灰度版本不能被全局选择() {
        // 构造 service 的代码与本文件其它用例相同
        com.kwiki.indexing.gray.IndexVersionKbScope scope =
                org.mockito.Mockito.mock(com.kwiki.indexing.gray.IndexVersionKbScope.class);
        org.mockito.Mockito.when(scope.isScoped(3)).thenReturn(true);
        service.setKbScope(scope);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.select(3, "admin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gray release version");
    }
```

- [ ] **Step 2：运行确认失败**

Run：`./mvnw -q test -Dtest=ScopedIndexingTest,AliasSwitchServiceTest`
Expected：`ScopedIndexingTest` 断言失败；`AliasSwitchServiceTest` 编译失败（无 `setKbScope`）。

- [ ] **Step 3：`FixedRangeRebuildScanner` 加范围过滤**

`processBatch` 中把版本号传给查询：

```java
        List<ResourceRow> rows = switch (range.getResourceType()) {
            case "PAGE" -> pageRows(range, version);
            case "ATTACHMENT" -> attachmentRows(range, multimodalParser, version);
```

替换两个查询方法：

```java
    private List<ResourceRow> pageRows(SearchIndexRebuildRange range, int version) {
        return jdbc.query("""
                SELECT p.id, p.current_published_revision_id, p.lifecycle_version
                FROM wiki_page p JOIN knowledge_base k ON k.id=p.kb_id
                WHERE p.id>? AND p.id<=? AND p.node_type='PAGE' AND p.status='ACTIVE'
                  AND p.current_published_revision_id IS NOT NULL AND k.status='ACTIVE'
                """ + com.kwiki.indexing.gray.IndexVersionKbScope.sqlFilter("p.kb_id") + """
                 ORDER BY p.id ASC LIMIT ?
                """, (rs, n) -> new ResourceRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                range.getLastSeenId(), range.getMaxId(), version, version, batchSize);
    }

    private List<ResourceRow> attachmentRows(SearchIndexRebuildRange range,
                                             boolean multimodalParser, int version) {
        // 多模态解析代把普通 PDF 附件纳入基线，导入来源由页面索引承载。
        String types = multimodalParser
                ? "('image/png','image/jpeg','image/gif','image/webp','application/pdf')"
                : "('image/png','image/jpeg','image/gif','image/webp')";
        return jdbc.query("""
                SELECT a.id FROM attachment a JOIN knowledge_base k ON k.id=a.kb_id
                WHERE a.id>? AND a.id<=? AND a.status='STORED' AND k.status='ACTIVE'
                  AND a.purpose='GENERAL'
                  AND LOWER(a.content_type) IN %s
                """.formatted(types) + com.kwiki.indexing.gray.IndexVersionKbScope.sqlFilter("a.kb_id") + """
                 ORDER BY a.id ASC LIMIT ?
                """, (rs, n) -> new ResourceRow(rs.getLong(1), null, 0),
                range.getLastSeenId(), range.getMaxId(), version, version, batchSize);
    }
```

> 若另一会话提交后这两个方法的 WHERE 条件已变化，保留其最新条件，只在 `ORDER BY` 前插入 `sqlFilter(...)` 片段并在 `batchSize` 前追加 `version, version` 两个参数。

- [ ] **Step 4：`SwitchCatchupProcessor` 加范围过滤**

`tailPageRows` / `tailAttachmentRows` 增加 `int version` 参数，调用处传入 `target.versionNumber()`（该方法所在处已持有 `Target(versionNumber, physicalName)`；按实际变量名传入）：

```java
    private List<ResourceRow> tailPageRows(SearchIndexRebuildRange range, int version) {
        return jdbc.query("""
                SELECT p.id, p.current_published_revision_id, p.lifecycle_version
                FROM wiki_page p JOIN knowledge_base k ON k.id=p.kb_id
                WHERE p.id>? AND p.id<=? AND p.node_type='PAGE' AND p.status='ACTIVE'
                  AND p.current_published_revision_id IS NOT NULL AND k.status='ACTIVE'
                """ + com.kwiki.indexing.gray.IndexVersionKbScope.sqlFilter("p.kb_id") + """
                 ORDER BY p.id ASC LIMIT ?
                """, (rs, row) -> new ResourceRow(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                range.getTailLastSeenId(), range.getTailMaxId(), version, version, batchSize);
    }

    private List<ResourceRow> tailAttachmentRows(SearchIndexRebuildRange range, int version) {
        return jdbc.query("""
                SELECT a.id FROM attachment a JOIN knowledge_base k ON k.id=a.kb_id
                WHERE a.id>? AND a.id<=? AND a.status='STORED' AND k.status='ACTIVE'
                  AND LOWER(a.content_type) IN ('image/png','image/jpeg','image/gif','image/webp')
                """ + com.kwiki.indexing.gray.IndexVersionKbScope.sqlFilter("a.kb_id") + """
                 ORDER BY a.id ASC LIMIT ?
                """, (rs, row) -> new ResourceRow(rs.getLong(1), null, 0),
                range.getTailLastSeenId(), range.getTailMaxId(), version, version, batchSize);
    }
```

> 同上：若附件类型条件已被另一会话改为按解析代区分，保留其条件，只插入范围片段与参数。

事件重放（`search_index_change_event`）无需过滤：重放产生的目标会在工作线程被范围判定跳过（Step 6）。

- [ ] **Step 5：`SearchIndexValidationService.effectiveResources` 加范围过滤**

```java
    private List<ResourceIdentity> effectiveResources(SearchIndexVersion version){
        int number=version.getVersionNumber();
        List<ResourceIdentity> result=new ArrayList<>();
        result.addAll(jdbc.query("""
                SELECT p.id,p.current_published_revision_id,p.lifecycle_version
                FROM wiki_page p JOIN knowledge_base k ON k.id=p.kb_id
                WHERE p.node_type='PAGE' AND p.status='ACTIVE'
                  AND p.current_published_revision_id IS NOT NULL AND k.status='ACTIVE'
                """+com.kwiki.indexing.gray.IndexVersionKbScope.sqlFilter("p.kb_id"),
                (rs,row)->new ResourceIdentity("PAGE",rs.getLong(1),rs.getLong(2),rs.getLong(3)),
                number,number));
        String attachmentTypes = com.kwiki.indexing.job.IndexingWorker.PARSER_VERSION_MULTIMODAL
                .equals(version.editableConfig().parserVersion())
                ? "('image/png','image/jpeg','image/gif','image/webp','application/pdf')"
                : "('image/png','image/jpeg','image/gif','image/webp')";
        result.addAll(jdbc.query("""
                SELECT a.id FROM attachment a JOIN knowledge_base k ON k.id=a.kb_id
                WHERE a.status='STORED' AND k.status='ACTIVE' AND a.purpose='GENERAL'
                  AND LOWER(a.content_type) IN %s
                """.formatted(attachmentTypes)+com.kwiki.indexing.gray.IndexVersionKbScope.sqlFilter("a.kb_id"),
                (rs,row)->new ResourceIdentity("ATTACHMENT",rs.getLong(1),null,0),
                number,number));
        return result;
    }
```

`SearchIndexValidationServiceTest` 中对 `jdbc.query(...)` 的 stub 若匹配了具体参数个数，改为 `any()` 形式的可变参数匹配（`ArgumentMatchers.<Object>any()` 追加两个），保证该测试继续通过。

- [ ] **Step 6：`IndexingWorker` 写入前范围判定**

新增字段与 setter（放在字段区与构造器之后）：

```java
    /** 灰度版本的知识库范围；未注入（离线测试）时视为全局版本。 */
    private com.kwiki.indexing.gray.IndexVersionKbScope kbScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKbScope(com.kwiki.indexing.gray.IndexVersionKbScope kbScope) {
        this.kbScope = kbScope;
    }

    private boolean acceptsKnowledgeBase(int indexVersion, long kbId) {
        return kbScope == null || kbScope.accepts(indexVersion, kbId);
    }
```

`executeFencedPageUpsert` 中，在生命周期判定之后、`writeFencedUpsert` 之前插入：

```java
        if (!acceptsKnowledgeBase(indexVersion, page.getKbId())) {
            return; // 灰度版本只收范围内知识库；范围外的目标直接完成、不写入
        }
```

`executeAttachmentUpsert` 中，在 `if (!attachment.isStored()) { return; }` 之后插入：

```java
        if (!acceptsKnowledgeBase(indexVersion, attachment.getKbId())) {
            return; // 灰度版本只收范围内知识库
        }
```

- [ ] **Step 7：灰度版本禁止全局选择**

`AliasSwitchService` 增加 setter 与守卫（守卫放在 `select` 方法里、`multimodalReadiness.requireReadyFor(...)` 之前）：

```java
    private com.kwiki.indexing.gray.IndexVersionKbScope kbScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKbScope(com.kwiki.indexing.gray.IndexVersionKbScope kbScope) {
        this.kbScope = kbScope;
    }
```

```java
        if (kbScope != null && kbScope.isScoped(targetVersion)) {
            throw new IllegalStateException("gray release version cannot be selected globally");
        }
```

`SearchIndexAdminQueryService`：同样增加 `setKbScope` setter；`view(...)` 中计算 `boolean scoped = kbScope != null && kbScope.isScoped(version.getVersionNumber());`，把 `select` 改为：

```java
        actions.put("select",!version.isSelected()&&!snapshot.dirty()&&!preparing
                && !multimodalBlocked && !scoped);
```

`VersionView` 末尾追加 `boolean kbScoped`，`new VersionView(..., Map.copyOf(actions))` 改为 `new VersionView(..., Map.copyOf(actions), scoped)`。全局搜索 `new VersionView(` / `VersionView(` 的其它构造处并补齐参数。

- [ ] **Step 8：灰度版本不占保留名额，结束后可清理**

`IndexVersionRetentionAdvisor` 固定保留「已选中 + 编号最新」共 2 个版本；灰度版本通常编号最新，会挤占名额且自身永远不会成为可清理。增加 setter 注入范围，并在计算保留集合时排除灰度版本：

```java
    private com.kwiki.indexing.gray.IndexVersionKbScope kbScope;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setKbScope(com.kwiki.indexing.gray.IndexVersionKbScope kbScope) {
        this.kbScope = kbScope;
    }

    private boolean scoped(SearchIndexVersion version) {
        return kbScope != null && kbScope.isScoped(version.getVersionNumber());
    }
```

`recommend()` 中「按编号倒序补足保留名额」的流改为先过滤掉灰度版本：

```java
        active.stream()
                .filter(version -> !scoped(version))
                .sorted(Comparator.comparingInt(SearchIndexVersion::getVersionNumber).reversed())
                .map(SearchIndexVersion::getVersionNumber)
                .filter(version -> !retained.contains(version))
                .limit(Math.max(0, DEFAULT_RETAINED_VERSIONS - retained.size()))
                .forEach(retained::add);
```

（`cleanupCandidate` 仍要求 `isAdminDisabled && !isWriteEnabled`：灰度结束时 `IndexVersionEnablementService.disable` 使其满足条件；进行中的灰度版本写入开启，不会被误判可清理。）

在 `IndexVersionRetentionAdvisorTest` 追加用例（按该文件既有方式构造 `repository` 与版本）：

```java
    @Test
    void 灰度版本不占保留名额_停用后成为可清理() {
        // 构造：v1 已选中、v2 全局版本、v3 灰度版本且已停用（adminDisabled=true, writeEnabled=false）
        com.kwiki.indexing.gray.IndexVersionKbScope scope =
                org.mockito.Mockito.mock(com.kwiki.indexing.gray.IndexVersionKbScope.class);
        org.mockito.Mockito.when(scope.isScoped(3)).thenReturn(true);
        IndexVersionRetentionAdvisor advisor = new IndexVersionRetentionAdvisor(repository);
        advisor.setKbScope(scope);
        var items = advisor.recommend().versions();
        org.assertj.core.api.Assertions.assertThat(items).filteredOn(item -> item.versionNumber() == 2)
                .singleElement().extracting(IndexVersionRetentionAdvisor.VersionRecommendation::retained).isEqualTo(true);
        org.assertj.core.api.Assertions.assertThat(items).filteredOn(item -> item.versionNumber() == 3)
                .singleElement().extracting(IndexVersionRetentionAdvisor.VersionRecommendation::cleanupCandidate).isEqualTo(true);
    }
```

- [ ] **Step 9：运行测试**

Run：`./mvnw -q test -Dtest=ScopedIndexingTest,AliasSwitchServiceTest,SearchIndexValidationServiceTest,IndexVersionRetentionAdvisorTest,FixedRangeRebuild*,SwitchPreparationServiceTest,IndexingWorker*`
Expected：全部通过。

- [ ] **Step 10：提交**

```bash
git add src/main/java/com/kwiki/indexing src/test/java/com/kwiki/indexing
git commit -m "feat(indexing): 重建、补齐、校验与写入按版本知识库范围处理，灰度版本禁止全局选择"
```

---

### Task 3：灰度领域：存储、解析器目录与状态机

**Files:**
- Create: `indexing/gray/GrayReleaseStatus.java`、`GrayRelease.java`、`GrayReleaseStore.java`、`JdbcGrayReleaseStore.java`、`ParserCatalog.java`、`GrayReleaseService.java`
- Modify: `indexing/version/SearchIndexRebuildRunRepository.java`（新增最新 run 查询）
- Test: `src/test/java/com/kwiki/indexing/gray/InMemoryGrayReleaseStore.java`、`GrayReleaseServiceTest.java`、`ParserCatalogTest.java`

**Interfaces:**
- Consumes：`IndexVersionKbScope#register`；`SearchIndexAdminService#createVersion(EditableIndexConfig)`；`SearchIndexVersionRepository#findBySelectedTrue()`、`#findByVersionNumber(int)`；`ManualIndexRebuildService#rebuild(int, String)`；`SwitchPreparationService#prepare(int)`；`SearchIndexValidationService#validate(int)`、`#currentReadyReport(int)`；`IndexVersionEnablementService#disable(int)`；`MultimodalSwitchReadiness#ready()`、`#missingConfiguration()`。
- Produces：
  - `enum GrayReleaseStatus { CREATED, SYNCING, SYNCED, SWITCHED, ENDED }`
  - `record GrayRelease(long id, String name, String parserVersion, int indexVersionNumber, GrayReleaseStatus status, String lastError, String createdBy, Instant createdAt, Instant switchedAt, Instant endedAt, List<GrayRelease.Kb> kbs)`，`record Kb(long kbId, String name)`
  - `interface GrayReleaseStore`（方法见 Step 3）
  - `record ParserCatalog.ParserOption(String id, String label, boolean available, String unavailableReason)`；`ParserCatalog#options()`、`#requireAvailable(String)`、`static label(String)`
  - `GrayReleaseService#create(String name, String parserVersion, List<Long> kbIds, String operator): GrayRelease`
  - `GrayReleaseService#sync(long id, String operator): GrayRelease`
  - `GrayReleaseService#advance(long id): GrayRelease`
  - `GrayReleaseService#switchTo(long id): GrayRelease`、`#switchBack(long id)`、`#end(long id)`
  - `GrayReleaseService#list(): List<GrayRelease>`、`#find(long id)`
  - `SearchIndexRebuildRunRepository#findFirstByVersionNumberOrderByIdDesc(int)`

- [ ] **Step 1：枚举与视图 record**

`GrayReleaseStatus.java`：

```java
package com.kwiki.indexing.gray;

/** 灰度状态：切回不是独立状态——SWITCHED 切回后回到 SYNCED（仍双写，可再次切换）。 */
public enum GrayReleaseStatus {
    CREATED, SYNCING, SYNCED, SWITCHED, ENDED
}
```

`GrayRelease.java`：

```java
package com.kwiki.indexing.gray;

import java.time.Instant;
import java.util.List;

/** 灰度发布的只读视图，含知识库名称，供服务与接口使用。 */
public record GrayRelease(long id, String name, String parserVersion, int indexVersionNumber,
                          GrayReleaseStatus status, String lastError, String createdBy,
                          Instant createdAt, Instant switchedAt, Instant endedAt, List<Kb> kbs) {

    public record Kb(long kbId, String name) { }

    public List<Long> kbIds() {
        return kbs.stream().map(Kb::kbId).toList();
    }
}
```

- [ ] **Step 2：`SearchIndexRebuildRunRepository` 增加方法**

```java
    Optional<SearchIndexRebuildRun> findFirstByVersionNumberOrderByIdDesc(int versionNumber);
```

- [ ] **Step 3：存储接口 `GrayReleaseStore`**

```java
package com.kwiki.indexing.gray;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 灰度发布存储。实现须保证 activeReleaseByKb 与唯一约束一致。 */
public interface GrayReleaseStore {

    /** 插入 CREATED 状态的灰度，返回 id。 */
    long insert(String name, String parserVersion, int indexVersionNumber, String createdBy);

    /** 插入知识库并设置 active_kb_id；违反唯一约束时抛 DataIntegrityViolationException。 */
    void insertKbs(long releaseId, Collection<Long> kbIds);

    Optional<GrayRelease> find(long id);

    List<GrayRelease> findAll();

    List<GrayRelease> findByStatus(GrayReleaseStatus status);

    /** 返回给定知识库中已在未结束灰度里的：kbId → releaseId。 */
    Map<Long, Long> activeReleaseByKb(Collection<Long> kbIds);

    void updateStatus(long id, GrayReleaseStatus status, String lastError);

    void rename(long id, String name);

    /** 进入 SWITCHED 并记录切换时间。 */
    void markSwitched(long id);

    /** 进入 ENDED、记录结束时间并释放 active_kb_id。 */
    void end(long id);

    /** 所有 SWITCHED 灰度的「物理索引名 → 解析器 → 知识库」。 */
    List<SwitchedRoute> switchedRoutes();

    record SwitchedRoute(String physicalName, String parserVersion, long kbId) { }
}
```

- [ ] **Step 4：`JdbcGrayReleaseStore`**

```java
package com.kwiki.indexing.gray;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 基于 JdbcTemplate 的灰度发布存储。 */
@Repository
public class JdbcGrayReleaseStore implements GrayReleaseStore {

    private static final String SELECT_RELEASE = """
            SELECT id, name, parser_version, index_version_number, status, last_error,
                   created_by, created_at, switched_at, ended_at
            FROM index_gray_release
            """;

    private final JdbcTemplate jdbc;

    public JdbcGrayReleaseStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long insert(String name, String parserVersion, int indexVersionNumber, String createdBy) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO index_gray_release (name, parser_version, index_version_number, status, created_by)
                    VALUES (?, ?, ?, 'CREATED', ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, name);
            statement.setString(2, parserVersion);
            statement.setInt(3, indexVersionNumber);
            statement.setString(4, createdBy);
            return statement;
        }, keys);
        Number key = keys.getKey();
        if (key == null) {
            throw new IllegalStateException("gray release insert returned no key");
        }
        return key.longValue();
    }

    @Override
    public void insertKbs(long releaseId, Collection<Long> kbIds) {
        List<Object[]> rows = kbIds.stream().distinct()
                .map(kbId -> new Object[] {releaseId, kbId, kbId}).toList();
        jdbc.batchUpdate("INSERT INTO index_gray_release_kb (release_id, kb_id, active_kb_id) VALUES (?, ?, ?)", rows);
    }

    @Override
    public Optional<GrayRelease> find(long id) {
        return query(SELECT_RELEASE + " WHERE id = ?", id).stream().findFirst();
    }

    @Override
    public List<GrayRelease> findAll() {
        return query(SELECT_RELEASE + " ORDER BY id DESC");
    }

    @Override
    public List<GrayRelease> findByStatus(GrayReleaseStatus status) {
        return query(SELECT_RELEASE + " WHERE status = ? ORDER BY id", status.name());
    }

    @Override
    public Map<Long, Long> activeReleaseByKb(Collection<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = kbIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Map<Long, Long> result = new LinkedHashMap<>();
        jdbc.query("SELECT active_kb_id, release_id FROM index_gray_release_kb WHERE active_kb_id IN ("
                        + placeholders + ")",
                (ResultSet rs) -> { result.put(rs.getLong(1), rs.getLong(2)); }, kbIds.toArray());
        return result;
    }

    @Override
    public void updateStatus(long id, GrayReleaseStatus status, String lastError) {
        jdbc.update("UPDATE index_gray_release SET status = ?, last_error = ? WHERE id = ?",
                status.name(), lastError, id);
    }

    @Override
    public void rename(long id, String name) {
        jdbc.update("UPDATE index_gray_release SET name = ? WHERE id = ?", name, id);
    }

    @Override
    public void markSwitched(long id) {
        jdbc.update("UPDATE index_gray_release SET status = 'SWITCHED', last_error = NULL,"
                + " switched_at = CURRENT_TIMESTAMP(6) WHERE id = ?", id);
    }

    @Override
    public void end(long id) {
        jdbc.update("UPDATE index_gray_release SET status = 'ENDED', ended_at = CURRENT_TIMESTAMP(6) WHERE id = ?", id);
        jdbc.update("UPDATE index_gray_release_kb SET active_kb_id = NULL WHERE release_id = ?", id);
    }

    @Override
    public List<SwitchedRoute> switchedRoutes() {
        return jdbc.query("""
                SELECT v.physical_name, r.parser_version, k.kb_id
                FROM index_gray_release r
                JOIN search_index_version v ON v.version_number = r.index_version_number
                JOIN index_gray_release_kb k ON k.release_id = r.id
                WHERE r.status = 'SWITCHED' AND k.active_kb_id IS NOT NULL AND v.deleted_at IS NULL
                """, (rs, row) -> new SwitchedRoute(rs.getString(1), rs.getString(2), rs.getLong(3)));
    }

    private List<GrayRelease> query(String sql, Object... args) {
        List<GrayRelease> releases = jdbc.query(sql, (rs, row) -> release(rs, List.of()), args);
        if (releases.isEmpty()) {
            return releases;
        }
        Map<Long, List<GrayRelease.Kb>> kbs = kbs(releases.stream().map(GrayRelease::id).toList());
        List<GrayRelease> result = new ArrayList<>(releases.size());
        for (GrayRelease release : releases) {
            result.add(new GrayRelease(release.id(), release.name(), release.parserVersion(),
                    release.indexVersionNumber(), release.status(), release.lastError(), release.createdBy(),
                    release.createdAt(), release.switchedAt(), release.endedAt(),
                    kbs.getOrDefault(release.id(), List.of())));
        }
        return result;
    }

    private Map<Long, List<GrayRelease.Kb>> kbs(List<Long> releaseIds) {
        String placeholders = releaseIds.stream().map(id -> "?").collect(Collectors.joining(","));
        Map<Long, List<GrayRelease.Kb>> result = new LinkedHashMap<>();
        jdbc.query("SELECT g.release_id, g.kb_id, COALESCE(b.name, CONCAT('知识库 #', g.kb_id))"
                        + " FROM index_gray_release_kb g LEFT JOIN knowledge_base b ON b.id = g.kb_id"
                        + " WHERE g.release_id IN (" + placeholders + ") ORDER BY g.kb_id",
                (ResultSet rs) -> {
                    result.computeIfAbsent(rs.getLong(1), key -> new ArrayList<>())
                            .add(new GrayRelease.Kb(rs.getLong(2), rs.getString(3)));
                }, releaseIds.toArray());
        return result;
    }

    private static GrayRelease release(ResultSet rs, List<GrayRelease.Kb> kbs) throws SQLException {
        return new GrayRelease(rs.getLong("id"), rs.getString("name"), rs.getString("parser_version"),
                rs.getInt("index_version_number"), GrayReleaseStatus.valueOf(rs.getString("status")),
                rs.getString("last_error"), rs.getString("created_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("switched_at")), instant(rs.getTimestamp("ended_at")), kbs);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
```

- [ ] **Step 5：测试用内存存储 `InMemoryGrayReleaseStore`**（`src/test/java/com/kwiki/indexing/gray/`）

```java
package com.kwiki.indexing.gray;

import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 与 JdbcGrayReleaseStore 语义一致的内存实现，供服务单测使用。 */
class InMemoryGrayReleaseStore implements GrayReleaseStore {

    final Map<Long, GrayRelease> releases = new LinkedHashMap<>();
    final Map<Long, Long> activeKb = new LinkedHashMap<>();
    final Map<Integer, String> physicalNames = new LinkedHashMap<>();
    private long nextId = 1;

    @Override
    public long insert(String name, String parserVersion, int indexVersionNumber, String createdBy) {
        long id = nextId++;
        releases.put(id, new GrayRelease(id, name, parserVersion, indexVersionNumber, GrayReleaseStatus.CREATED,
                null, createdBy, Instant.EPOCH, null, null, List.of()));
        physicalNames.put(indexVersionNumber, "kwiki-chunks-v" + indexVersionNumber);
        return id;
    }

    @Override
    public void insertKbs(long releaseId, Collection<Long> kbIds) {
        for (Long kbId : kbIds) {
            if (activeKb.containsKey(kbId)) {
                throw new DataIntegrityViolationException("duplicate active kb " + kbId);
            }
        }
        List<GrayRelease.Kb> kbs = new ArrayList<>();
        for (Long kbId : kbIds) {
            activeKb.put(kbId, releaseId);
            kbs.add(new GrayRelease.Kb(kbId, "知识库 " + kbId));
        }
        GrayRelease r = releases.get(releaseId);
        releases.put(releaseId, new GrayRelease(r.id(), r.name(), r.parserVersion(), r.indexVersionNumber(),
                r.status(), r.lastError(), r.createdBy(), r.createdAt(), r.switchedAt(), r.endedAt(), kbs));
    }

    @Override
    public Optional<GrayRelease> find(long id) {
        return Optional.ofNullable(releases.get(id));
    }

    @Override
    public List<GrayRelease> findAll() {
        return new ArrayList<>(releases.values());
    }

    @Override
    public List<GrayRelease> findByStatus(GrayReleaseStatus status) {
        return releases.values().stream().filter(r -> r.status() == status).toList();
    }

    @Override
    public Map<Long, Long> activeReleaseByKb(Collection<Long> kbIds) {
        Map<Long, Long> result = new LinkedHashMap<>();
        kbIds.forEach(kbId -> { if (activeKb.containsKey(kbId)) result.put(kbId, activeKb.get(kbId)); });
        return result;
    }

    @Override
    public void updateStatus(long id, GrayReleaseStatus status, String lastError) {
        GrayRelease r = releases.get(id);
        releases.put(id, new GrayRelease(r.id(), r.name(), r.parserVersion(), r.indexVersionNumber(), status,
                lastError, r.createdBy(), r.createdAt(), r.switchedAt(), r.endedAt(), r.kbs()));
    }

    @Override
    public void rename(long id, String name) {
        GrayRelease r = releases.get(id);
        releases.put(id, new GrayRelease(r.id(), name, r.parserVersion(), r.indexVersionNumber(), r.status(),
                r.lastError(), r.createdBy(), r.createdAt(), r.switchedAt(), r.endedAt(), r.kbs()));
    }

    @Override
    public void markSwitched(long id) {
        GrayRelease r = releases.get(id);
        releases.put(id, new GrayRelease(r.id(), r.name(), r.parserVersion(), r.indexVersionNumber(),
                GrayReleaseStatus.SWITCHED, null, r.createdBy(), r.createdAt(), Instant.EPOCH, r.endedAt(), r.kbs()));
    }

    @Override
    public void end(long id) {
        GrayRelease r = releases.get(id);
        releases.put(id, new GrayRelease(r.id(), r.name(), r.parserVersion(), r.indexVersionNumber(),
                GrayReleaseStatus.ENDED, r.lastError(), r.createdBy(), r.createdAt(), r.switchedAt(), Instant.EPOCH,
                r.kbs()));
        activeKb.values().removeIf(releaseId -> releaseId == id);
    }

    @Override
    public List<SwitchedRoute> switchedRoutes() {
        List<SwitchedRoute> routes = new ArrayList<>();
        for (GrayRelease r : findByStatus(GrayReleaseStatus.SWITCHED)) {
            for (Long kbId : r.kbIds()) {
                routes.add(new SwitchedRoute(physicalNames.get(r.indexVersionNumber()), r.parserVersion(), kbId));
            }
        }
        return routes;
    }
}
```

- [ ] **Step 6：`ParserCatalog` 的失败测试 `ParserCatalogTest`**

```java
package com.kwiki.indexing.gray;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.wiki.api.ConflictException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParserCatalogTest {

    @Test
    void 列出两个解析器并给出显示名与可用性() {
        MultimodalSwitchReadiness readiness = mock(MultimodalSwitchReadiness.class);
        when(readiness.ready()).thenReturn(false);
        when(readiness.missingConfiguration()).thenReturn(List.of("kwiki.indexing.multimodal.vision.base-url"));
        ParserCatalog catalog = new ParserCatalog(readiness);

        assertThat(catalog.options()).extracting(ParserCatalog.ParserOption::id)
                .containsExactly("kwiki-parse-1", "kwiki-parse-2");
        assertThat(catalog.options().get(0).label()).isEqualTo("tika-v1");
        assertThat(catalog.options().get(0).available()).isTrue();
        assertThat(catalog.options().get(1).label()).isEqualTo("pdfbox-v2");
        assertThat(catalog.options().get(1).available()).isFalse();
        assertThat(catalog.options().get(1).unavailableReason()).contains("base-url");
    }

    @Test
    void 不可用或未知解析器被拒绝() {
        MultimodalSwitchReadiness readiness = mock(MultimodalSwitchReadiness.class);
        when(readiness.ready()).thenReturn(false);
        when(readiness.missingConfiguration()).thenReturn(List.of("x"));
        ParserCatalog catalog = new ParserCatalog(readiness);
        assertThatThrownBy(() -> catalog.requireAvailable("kwiki-parse-2")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> catalog.requireAvailable("nope")).isInstanceOf(ConflictException.class);
        catalog.requireAvailable("kwiki-parse-1");
        assertThat(ParserCatalog.label("kwiki-parse-2")).isEqualTo("pdfbox-v2");
        assertThat(ParserCatalog.label("custom")).isEqualTo("custom");
    }
}
```

- [ ] **Step 7：实现 `ParserCatalog`**

```java
package com.kwiki.indexing.gray;

import com.kwiki.indexing.config.MultimodalSwitchReadiness;
import com.kwiki.indexing.job.IndexingWorker;
import com.kwiki.wiki.api.ConflictException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 当前部署可用的解析器目录。新增解析器时在此登记（二期改为注册表）。 */
@Component
public class ParserCatalog {

    private static final Map<String, String> LABELS = Map.of(
            IndexingWorker.PARSER_VERSION, "tika-v1",
            IndexingWorker.PARSER_VERSION_MULTIMODAL, "pdfbox-v2");

    private final MultimodalSwitchReadiness readiness;

    public ParserCatalog(MultimodalSwitchReadiness readiness) {
        this.readiness = readiness;
    }

    public record ParserOption(String id, String label, boolean available, String unavailableReason) { }

    public static String label(String parserVersion) {
        return LABELS.getOrDefault(parserVersion, parserVersion);
    }

    public List<ParserOption> options() {
        boolean multimodalReady = readiness.ready();
        return List.of(
                new ParserOption(IndexingWorker.PARSER_VERSION, label(IndexingWorker.PARSER_VERSION), true, null),
                new ParserOption(IndexingWorker.PARSER_VERSION_MULTIMODAL,
                        label(IndexingWorker.PARSER_VERSION_MULTIMODAL), multimodalReady,
                        multimodalReady ? null : "缺少配置：" + String.join("、", readiness.missingConfiguration())));
    }

    public void requireAvailable(String parserVersion) {
        ParserOption option = options().stream().filter(item -> item.id().equals(parserVersion)).findFirst()
                .orElseThrow(() -> new ConflictException("未知的解析器：" + parserVersion));
        if (!option.available()) {
            throw new ConflictException("解析器 " + option.label() + " 暂不可用，" + option.unavailableReason());
        }
    }
}
```

> 若 `IndexingWorker.PARSER_VERSION` / `PARSER_VERSION_MULTIMODAL` 常量名在另一会话提交后有变，按实际常量名替换（取值应分别为 `kwiki-parse-1` / `kwiki-parse-2`）。

- [ ] **Step 8：`GrayReleaseService` 的失败测试 `GrayReleaseServiceTest`**

```java
package com.kwiki.indexing.gray;

import com.kwiki.indexing.search.ChunkMappingBuilder;
import com.kwiki.indexing.version.*;
import com.kwiki.wiki.api.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GrayReleaseServiceTest {

    private final InMemoryGrayReleaseStore store = new InMemoryGrayReleaseStore();
    private final SearchIndexAdminService admin = mock(SearchIndexAdminService.class);
    private final SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
    private final IndexVersionKbScope scope = mock(IndexVersionKbScope.class);
    private final ManualIndexRebuildService rebuilds = mock(ManualIndexRebuildService.class);
    private final SwitchPreparationService preparations = mock(SwitchPreparationService.class);
    private final SearchIndexValidationService validations = mock(SearchIndexValidationService.class);
    private final IndexVersionEnablementService enablement = mock(IndexVersionEnablementService.class);
    private final SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
    private final ParserCatalog parsers = mock(ParserCatalog.class);
    private GrayReleaseService service;

    private final EditableIndexConfig globalConfig =
            new EditableIndexConfig("kwiki-parse-1", "kwiki-chunk-1", "default", "model", 1024, 3);

    @BeforeEach
    void setUp() {
        service = new GrayReleaseService(store, admin, versions, scope, rebuilds, preparations,
                validations, enablement, runs, parsers);
        String hash = new ChunkMappingBuilder().mappingHash(1024, 3);
        when(versions.findBySelectedTrue()).thenReturn(Optional.of(
                SearchIndexVersion.bootstrapped(1, "kwiki-chunks-v1", globalConfig, hash)));
        when(admin.createVersion(any())).thenAnswer(invocation -> SearchIndexVersion.bootstrapped(
                2, "kwiki-chunks-v2", invocation.getArgument(0), hash));
    }

    @Test
    void 创建灰度_以全局配置为基础替换解析器并登记知识库范围() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L, 9L), "admin");

        verify(parsers).requireAvailable("kwiki-parse-2");
        verify(admin).createVersion(new EditableIndexConfig("kwiki-parse-2", "kwiki-chunk-1", "default", "model", 1024, 3));
        verify(scope).register(2, List.of(7L, 9L));
        assertThat(created.status()).isEqualTo(GrayReleaseStatus.CREATED);
        assertThat(created.indexVersionNumber()).isEqualTo(2);
        assertThat(created.name()).isEqualTo("pdfbox-v2 灰度 #1");
        assertThat(created.kbIds()).containsExactly(7L, 9L);
    }

    @Test
    void 知识库已在其他未结束灰度中时拒绝创建且不建版本() {
        store.insert("old", "kwiki-parse-2", 5, "admin");
        store.insertKbs(1, List.of(9L));
        assertThatThrownBy(() -> service.create(null, "kwiki-parse-2", List.of(7L, 9L), "admin"))
                .isInstanceOf(ConflictException.class).hasMessageContaining("9");
        verify(admin, never()).createVersion(any());
    }

    @Test
    void 空知识库列表拒绝创建() {
        assertThatThrownBy(() -> service.create("x", "kwiki-parse-2", List.of(), "admin"))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void 开始同步_发起重建并进入同步中() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(versionWrites(false)));
        GrayRelease syncing = service.sync(created.id(), "admin");
        verify(rebuilds).rebuild(2, "admin");
        assertThat(syncing.status()).isEqualTo(GrayReleaseStatus.SYNCING);
    }

    @Test
    void 推进_重建完成后补齐_补齐就绪后校验_通过即已同步() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.updateStatus(created.id(), GrayReleaseStatus.SYNCING, null);

        SearchIndexRebuildRun run = mock(SearchIndexRebuildRun.class);
        when(runs.findFirstByVersionNumberOrderByIdDesc(2)).thenReturn(Optional.of(run));
        when(run.state()).thenReturn(RebuildRunState.COMPLETED);
        when(run.switchState()).thenReturn(IndexSwitchState.NONE);
        service.advance(created.id());
        verify(preparations).prepare(2);

        when(run.switchState()).thenReturn(IndexSwitchState.READY);
        when(validations.currentReadyReport(2)).thenReturn(Optional.empty());
        SearchIndexValidationReport passed = mock(SearchIndexValidationReport.class);
        when(passed.getStatus()).thenReturn("PASS");
        when(validations.validate(2)).thenReturn(passed);
        assertThat(service.advance(created.id()).status()).isEqualTo(GrayReleaseStatus.SYNCED);
    }

    @Test
    void 推进_重建失败时停留在同步中并记录原因() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.updateStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        SearchIndexRebuildRun run = mock(SearchIndexRebuildRun.class);
        when(runs.findFirstByVersionNumberOrderByIdDesc(2)).thenReturn(Optional.of(run));
        when(run.state()).thenReturn(RebuildRunState.FAILED);
        when(run.getErrorSummary()).thenReturn("embedding timeout");
        GrayRelease after = service.advance(created.id());
        assertThat(after.status()).isEqualTo(GrayReleaseStatus.SYNCING);
        assertThat(after.lastError()).contains("embedding timeout");
    }

    @Test
    void 推进_校验未通过时记录摘要() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.updateStatus(created.id(), GrayReleaseStatus.SYNCING, null);
        SearchIndexRebuildRun run = mock(SearchIndexRebuildRun.class);
        when(runs.findFirstByVersionNumberOrderByIdDesc(2)).thenReturn(Optional.of(run));
        when(run.state()).thenReturn(RebuildRunState.COMPLETED);
        when(run.switchState()).thenReturn(IndexSwitchState.READY);
        when(validations.currentReadyReport(2)).thenReturn(Optional.empty());
        SearchIndexValidationReport failed = mock(SearchIndexValidationReport.class);
        when(failed.getStatus()).thenReturn("FAIL");
        when(failed.getSummary()).thenReturn("missingResources=3");
        when(validations.validate(2)).thenReturn(failed);
        GrayRelease after = service.advance(created.id());
        assertThat(after.status()).isEqualTo(GrayReleaseStatus.SYNCING);
        assertThat(after.lastError()).contains("missingResources=3");
    }

    @Test
    void 切换与切回_只允许在对应状态下进行() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        assertThatThrownBy(() -> service.switchTo(created.id())).isInstanceOf(ConflictException.class);

        store.updateStatus(created.id(), GrayReleaseStatus.SYNCED, null);
        when(validations.currentReadyReport(2)).thenReturn(Optional.of(mock(SearchIndexValidationReport.class)));
        assertThat(service.switchTo(created.id()).status()).isEqualTo(GrayReleaseStatus.SWITCHED);
        assertThat(service.switchBack(created.id()).status()).isEqualTo(GrayReleaseStatus.SYNCED);
        assertThatThrownBy(() -> service.switchBack(created.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void 切换前校验报告已失效则拒绝() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.updateStatus(created.id(), GrayReleaseStatus.SYNCED, null);
        when(validations.currentReadyReport(2)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.switchTo(created.id())).isInstanceOf(ConflictException.class);
    }

    @Test
    void 结束灰度_停用写入并释放知识库_索引保留() {
        GrayRelease created = service.create(null, "kwiki-parse-2", List.of(7L), "admin");
        store.updateStatus(created.id(), GrayReleaseStatus.SWITCHED, null);
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(versionWrites(true)));
        GrayRelease ended = service.end(created.id());
        verify(enablement).disable(2);
        assertThat(ended.status()).isEqualTo(GrayReleaseStatus.ENDED);
        assertThat(store.activeReleaseByKb(List.of(7L))).isEmpty();
        assertThatThrownBy(() -> service.end(created.id())).isInstanceOf(ConflictException.class);
    }

    private SearchIndexVersion versionWrites(boolean writeEnabled) {
        SearchIndexVersion version = mock(SearchIndexVersion.class);
        when(version.isWriteEnabled()).thenReturn(writeEnabled);
        when(version.getVersionNumber()).thenReturn(2);
        return version;
    }
}
```

- [ ] **Step 9：运行确认失败**

Run：`./mvnw -q test -Dtest=GrayReleaseServiceTest,ParserCatalogTest`
Expected：编译失败（`GrayReleaseService` 不存在）；`ParserCatalogTest` 在 Step 7 完成后已可通过。

- [ ] **Step 10：实现 `GrayReleaseService`**

```java
package com.kwiki.indexing.gray;

import com.kwiki.indexing.version.*;
import com.kwiki.wiki.api.ConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 灰度发布状态机：创建（建限定范围的索引版本）→ 同步（重建 → 补齐 → 校验，由 advance 推进）
 * → 切换 / 切回 → 结束（停用灰度版本写入，索引保留待手动删除）。
 */
@Service
public class GrayReleaseService {

    private final GrayReleaseStore store;
    private final SearchIndexAdminService admin;
    private final SearchIndexVersionRepository versions;
    private final IndexVersionKbScope scope;
    private final ManualIndexRebuildService rebuilds;
    private final SwitchPreparationService preparations;
    private final SearchIndexValidationService validations;
    private final IndexVersionEnablementService enablement;
    private final SearchIndexRebuildRunRepository runs;
    private final ParserCatalog parsers;

    public GrayReleaseService(GrayReleaseStore store, SearchIndexAdminService admin,
                              SearchIndexVersionRepository versions, IndexVersionKbScope scope,
                              ManualIndexRebuildService rebuilds, SwitchPreparationService preparations,
                              SearchIndexValidationService validations, IndexVersionEnablementService enablement,
                              SearchIndexRebuildRunRepository runs, ParserCatalog parsers) {
        this.store = store;
        this.admin = admin;
        this.versions = versions;
        this.scope = scope;
        this.rebuilds = rebuilds;
        this.preparations = preparations;
        this.validations = validations;
        this.enablement = enablement;
        this.runs = runs;
        this.parsers = parsers;
    }

    public List<GrayRelease> list() {
        return store.findAll();
    }

    public GrayRelease find(long id) {
        return store.find(id).orElseThrow(() -> new ConflictException("灰度不存在：" + id));
    }

    @Transactional
    public GrayRelease create(String name, String parserVersion, List<Long> kbIds, String operator) {
        List<Long> distinct = kbIds == null ? List.of() : kbIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            throw new ConflictException("请至少选择一个知识库");
        }
        parsers.requireAvailable(parserVersion);
        Map<Long, Long> conflicts = store.activeReleaseByKb(distinct);
        if (!conflicts.isEmpty()) {
            throw new ConflictException("以下知识库已在其他灰度中：" + conflicts.keySet());
        }
        EditableIndexConfig base = versions.findBySelectedTrue()
                .orElseThrow(() -> new ConflictException("尚无已发布的全局索引版本，无法创建灰度"))
                .editableConfig();
        EditableIndexConfig config = new EditableIndexConfig(parserVersion, base.chunkerVersion(),
                base.embeddingProvider(), base.embeddingModel(), base.embeddingDimensions(),
                base.mappingSchemaVersion());
        SearchIndexVersion version = admin.createVersion(config);
        scope.register(version.getVersionNumber(), distinct);
        long id = store.insert("pending", parserVersion, version.getVersionNumber(), operator);
        try {
            store.insertKbs(id, distinct);
        } catch (DataIntegrityViolationException raced) {
            throw new ConflictException("所选知识库刚被加入其他灰度，请刷新后重试");
        }
        String finalName = name == null || name.isBlank() ? ParserCatalog.label(parserVersion) + " 灰度 #" + id : name.trim();
        store.rename(id, finalName);
        return find(id);
    }

    @Transactional
    public GrayRelease sync(long id, String operator) {
        GrayRelease release = find(id);
        if (release.status() != GrayReleaseStatus.CREATED && release.status() != GrayReleaseStatus.SYNCING) {
            throw new ConflictException("当前状态不能开始同步：" + release.status());
        }
        SearchIndexVersion version = versions.findByVersionNumber(release.indexVersionNumber())
                .orElseThrow(() -> new ConflictException("灰度索引版本不存在"));
        // 写入已开启说明重建与补齐已完成，只需重新推进校验；否则重新发起重建。
        if (!version.isWriteEnabled()) {
            rebuilds.rebuild(release.indexVersionNumber(), operator);
        }
        store.updateStatus(id, GrayReleaseStatus.SYNCING, null);
        return find(id);
    }

    /** 由同步驱动器定时调用：按重建 → 补齐 → 校验推进一步。 */
    @Transactional
    public GrayRelease advance(long id) {
        GrayRelease release = find(id);
        if (release.status() != GrayReleaseStatus.SYNCING) {
            return release;
        }
        int number = release.indexVersionNumber();
        SearchIndexRebuildRun run = runs.findFirstByVersionNumberOrderByIdDesc(number).orElse(null);
        if (run == null || run.state().active()) {
            return release;
        }
        if (run.state() != RebuildRunState.COMPLETED) {
            store.updateStatus(id, GrayReleaseStatus.SYNCING, "同步失败：" + safe(run.getErrorSummary())
                    + "。可点击「开始同步」重试");
            return find(id);
        }
        switch (run.switchState()) {
            case NONE -> preparations.prepare(number);
            case FAILED -> store.updateStatus(id, GrayReleaseStatus.SYNCING, "补齐失败，可点击「开始同步」重试");
            case READY -> {
                if (validations.currentReadyReport(number).isPresent()) {
                    store.updateStatus(id, GrayReleaseStatus.SYNCED, null);
                } else {
                    SearchIndexValidationReport report = validations.validate(number);
                    if ("PASS".equals(report.getStatus())) {
                        store.updateStatus(id, GrayReleaseStatus.SYNCED, null);
                    } else {
                        store.updateStatus(id, GrayReleaseStatus.SYNCING, "校验未通过：" + safe(report.getSummary()));
                    }
                }
            }
            default -> { /* PREPARING：补齐进行中，等待下一轮 */ }
        }
        return find(id);
    }

    @Transactional
    public GrayRelease switchTo(long id) {
        GrayRelease release = find(id);
        if (release.status() != GrayReleaseStatus.SYNCED) {
            throw new ConflictException("只有同步完成的灰度才能切换");
        }
        if (validations.currentReadyReport(release.indexVersionNumber()).isEmpty()) {
            store.updateStatus(id, GrayReleaseStatus.SYNCING, "校验报告已失效，正在重新校验");
            throw new ConflictException("校验报告已失效，已重新进入同步，请稍后再切换");
        }
        store.markSwitched(id);
        return find(id);
    }

    @Transactional
    public GrayRelease switchBack(long id) {
        GrayRelease release = find(id);
        if (release.status() != GrayReleaseStatus.SWITCHED) {
            throw new ConflictException("灰度未处于已切换状态");
        }
        store.updateStatus(id, GrayReleaseStatus.SYNCED, null);
        return find(id);
    }

    @Transactional
    public GrayRelease end(long id) {
        GrayRelease release = find(id);
        if (release.status() == GrayReleaseStatus.ENDED) {
            throw new ConflictException("灰度已结束");
        }
        versions.findByVersionNumber(release.indexVersionNumber())
                .filter(SearchIndexVersion::isWriteEnabled)
                .ifPresent(version -> enablement.disable(version.getVersionNumber()));
        store.end(id);
        return find(id);
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "未知原因";
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
```

- [ ] **Step 11：运行确认通过**

Run：`./mvnw -q test -Dtest=GrayReleaseServiceTest,ParserCatalogTest`
Expected：全部通过。若 `SearchIndexRebuildRun` / `SearchIndexVersion` / `SearchIndexValidationReport` 是 `final` 类导致 Mockito 无法 mock，确认项目已启用 inline mock maker（`src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`）；未启用则改用真实对象（参照 `SearchIndexValidationServiceTest` 构造 run 与 report 的写法）。

- [ ] **Step 12：提交**

```bash
git add src/main/java/com/kwiki/indexing src/test/java/com/kwiki/indexing/gray
git commit -m "feat(indexing): 新增灰度发布存储、解析器目录与状态机"
```

---

### Task 4：同步驱动器

**Files:**
- Create: `src/main/java/com/kwiki/indexing/gray/GrayReleaseSyncDriver.java`
- Test: `src/test/java/com/kwiki/indexing/gray/GrayReleaseSyncDriverTest.java`

**Interfaces:**
- Consumes：`GrayReleaseStore#findByStatus`、`GrayReleaseService#advance(long)`。
- Produces：定时任务，配置项 `kwiki.indexing.gray.sync-interval`（默认 `10s`）。

- [ ] **Step 1：失败测试**

```java
package com.kwiki.indexing.gray;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.*;

class GrayReleaseSyncDriverTest {

    @Test
    void 逐个推进同步中的灰度_单个失败不影响其它() {
        InMemoryGrayReleaseStore store = new InMemoryGrayReleaseStore();
        long first = store.insert("a", "kwiki-parse-2", 2, "admin");
        long second = store.insert("b", "kwiki-parse-2", 3, "admin");
        store.updateStatus(first, GrayReleaseStatus.SYNCING, null);
        store.updateStatus(second, GrayReleaseStatus.SYNCING, null);
        GrayReleaseService service = mock(GrayReleaseService.class);
        when(service.advance(first)).thenThrow(new IllegalStateException("boom"));

        new GrayReleaseSyncDriver(store, service).tick();

        verify(service).advance(first);
        verify(service).advance(second);
    }

    @Test
    void 没有同步中的灰度时不做任何事() {
        GrayReleaseService service = mock(GrayReleaseService.class);
        new GrayReleaseSyncDriver(new InMemoryGrayReleaseStore(), service).tick();
        verifyNoInteractions(service);
    }
}
```

- [ ] **Step 2：运行确认失败**：`./mvnw -q test -Dtest=GrayReleaseSyncDriverTest`，预期编译失败。

- [ ] **Step 3：实现**

```java
package com.kwiki.indexing.gray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定时推进同步中的灰度：重建完成 → 补齐 → 校验。单个灰度失败只记录日志。 */
@Component
public class GrayReleaseSyncDriver {

    private static final Logger log = LoggerFactory.getLogger(GrayReleaseSyncDriver.class);

    private final GrayReleaseStore store;
    private final GrayReleaseService service;

    public GrayReleaseSyncDriver(GrayReleaseStore store, GrayReleaseService service) {
        this.store = store;
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${kwiki.indexing.gray.sync-interval:10s}")
    public void tick() {
        for (GrayRelease release : store.findByStatus(GrayReleaseStatus.SYNCING)) {
            try {
                service.advance(release.id());
            } catch (RuntimeException failure) {
                log.warn("gray release {} advance failed: {}", release.id(), failure.getMessage());
            }
        }
    }
}
```

- [ ] **Step 4：运行确认通过并提交**

Run：`./mvnw -q test -Dtest=GrayReleaseSyncDriverTest`，预期 2 passed。

```bash
git add src/main/java/com/kwiki/indexing/gray/GrayReleaseSyncDriver.java src/test/java/com/kwiki/indexing/gray/GrayReleaseSyncDriverTest.java
git commit -m "feat(indexing): 新增灰度同步驱动器，自动推进补齐与校验"
```

---

### Task 5：读路由

**Files:**
- Create: `indexing/gray/ReadRouting.java`、`indexing/gray/GrayReadRoutes.java`、`infrastructure/elasticsearch/EsReadRouting.java`
- Modify: `infrastructure/elasticsearch/VectorRecallAdapter.java`、`Bm25RecallAdapter.java`、`EsChunkLookup.java`
- Test: `src/test/java/com/kwiki/indexing/gray/GrayReadRoutesTest.java`、`src/test/java/com/kwiki/infrastructure/elasticsearch/EsReadRoutingTest.java`

**Interfaces:**
- Consumes：`GrayReleaseStore#switchedRoutes()`。
- Produces：
  - `record ReadRouting(Map<String, Set<Long>> kbIdsByGrayIndex)`：`isEmpty()`、`switchedKbIds(): Set<Long>`、`static ReadRouting none()`
  - `GrayReadRoutes#current(): ReadRouting`、`#switchedParserFor(long kbId): Optional<String>`
  - `EsReadRouting.indices(ReadRouting): List<String>`（首项恒为 `kwiki-chunks`）、`EsReadRouting.filter(ReadRouting): Optional<Query>`

- [ ] **Step 1：失败测试 `GrayReadRoutesTest`**

```java
package com.kwiki.indexing.gray;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class GrayReadRoutesTest {

    @Test
    void 只把已切换灰度的知识库路由到灰度物理索引() {
        InMemoryGrayReleaseStore store = new InMemoryGrayReleaseStore();
        long switched = store.insert("a", "kwiki-parse-2", 4, "admin");
        store.insertKbs(switched, List.of(7L, 9L));
        store.markSwitched(switched);
        long synced = store.insert("b", "kwiki-parse-2", 5, "admin");
        store.insertKbs(synced, List.of(11L));
        store.updateStatus(synced, GrayReleaseStatus.SYNCED, null);

        GrayReadRoutes routes = new GrayReadRoutes(store);
        ReadRouting routing = routes.current();

        assertThat(routing.kbIdsByGrayIndex()).isEqualTo(Map.of("kwiki-chunks-v4", Set.of(7L, 9L)));
        assertThat(routing.switchedKbIds()).containsExactlyInAnyOrder(7L, 9L);
        assertThat(routes.switchedParserFor(7L)).contains("kwiki-parse-2");
        assertThat(routes.switchedParserFor(11L)).isEmpty();
    }

    @Test
    void 无切换灰度时路由为空() {
        assertThat(new GrayReadRoutes(new InMemoryGrayReleaseStore()).current().isEmpty()).isTrue();
    }
}
```

- [ ] **Step 2：失败测试 `EsReadRoutingTest`**

```java
package com.kwiki.infrastructure.elasticsearch;

import com.kwiki.indexing.gray.ReadRouting;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EsReadRoutingTest {

    @Test
    void 无路由时只查别名且不加过滤() {
        assertThat(EsReadRouting.indices(ReadRouting.none())).containsExactly("kwiki-chunks");
        assertThat(EsReadRouting.filter(ReadRouting.none())).isEmpty();
    }

    @Test
    void 有路由时同时查别名与灰度索引_并按索引限定知识库() {
        ReadRouting routing = new ReadRouting(Map.of("kwiki-chunks-v4", Set.of(7L, 9L)));
        assertThat(EsReadRouting.indices(routing)).containsExactly("kwiki-chunks", "kwiki-chunks-v4");
        String query = EsReadRouting.filter(routing).orElseThrow().toString();
        // 灰度索引分支：_index = v4 且 kbId ∈ {7,9}
        assertThat(query).contains("kwiki-chunks-v4").contains("_index").contains("kbId");
        // 别名分支：排除灰度索引，并排除已切换的知识库
        assertThat(query).contains("must_not");
    }
}
```

- [ ] **Step 3：运行确认失败**：`./mvnw -q test -Dtest=GrayReadRoutesTest,EsReadRoutingTest`，预期编译失败。

- [ ] **Step 4：实现 `ReadRouting`、`GrayReadRoutes`、`EsReadRouting`**

`ReadRouting.java`：

```java
package com.kwiki.indexing.gray;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** 读路由快照：已切换灰度的「物理索引 → 知识库集合」；其余知识库读全局别名。 */
public record ReadRouting(Map<String, Set<Long>> kbIdsByGrayIndex) {

    public ReadRouting {
        kbIdsByGrayIndex = Map.copyOf(kbIdsByGrayIndex);
    }

    public static ReadRouting none() {
        return new ReadRouting(Map.of());
    }

    public boolean isEmpty() {
        return kbIdsByGrayIndex.isEmpty();
    }

    public Set<Long> switchedKbIds() {
        return kbIdsByGrayIndex.values().stream().flatMap(Set::stream).collect(Collectors.toUnmodifiableSet());
    }
}
```

`GrayReadRoutes.java`：

```java
package com.kwiki.indexing.gray;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** 读路由与按知识库的解析器查询；每次调用读取一次数据库，切换在下一次查询即生效。 */
@Component
public class GrayReadRoutes {

    private final GrayReleaseStore store;

    public GrayReadRoutes(GrayReleaseStore store) {
        this.store = store;
    }

    public ReadRouting current() {
        Map<String, Set<Long>> grouped = new LinkedHashMap<>();
        for (GrayReleaseStore.SwitchedRoute route : store.switchedRoutes()) {
            grouped.computeIfAbsent(route.physicalName(), key -> new LinkedHashSet<>()).add(route.kbId());
        }
        return new ReadRouting(grouped);
    }

    public Optional<String> switchedParserFor(long kbId) {
        return store.switchedRoutes().stream().filter(route -> route.kbId() == kbId)
                .map(GrayReleaseStore.SwitchedRoute::parserVersion).findFirst();
    }
}
```

`EsReadRouting.java`：

```java
package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.kwiki.indexing.gray.ReadRouting;
import com.kwiki.indexing.search.ElasticsearchIndexManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 读路由 → ES：一次查询同时覆盖全局别名与已切换灰度的物理索引。
 * 灰度索引的文档只保留该灰度的知识库；别名文档排除所有已切换到灰度的知识库。
 */
public final class EsReadRouting {

    private EsReadRouting() {
    }

    public static List<String> indices(ReadRouting routing) {
        List<String> indices = new ArrayList<>();
        indices.add(ElasticsearchIndexManager.ALIAS);
        indices.addAll(routing.kbIdsByGrayIndex().keySet().stream().sorted().toList());
        return indices;
    }

    public static Optional<Query> filter(ReadRouting routing) {
        if (routing.isEmpty()) {
            return Optional.empty();
        }
        List<FieldValue> grayIndices = routing.kbIdsByGrayIndex().keySet().stream().sorted()
                .map(FieldValue::of).toList();
        List<FieldValue> switchedKbs = longs(routing.switchedKbIds());
        return Optional.of(Query.of(query -> query.bool(bool -> {
            // 别名分支：不是灰度索引的文档，且知识库未切换到灰度
            bool.should(should -> should.bool(alias -> alias
                    .mustNot(not -> not.terms(terms -> terms.field("_index").terms(values -> values.value(grayIndices))))
                    .mustNot(not -> not.terms(terms -> terms.field("kbId").terms(values -> values.value(switchedKbs))))));
            // 灰度分支：每个灰度索引只保留其知识库
            for (Map.Entry<String, Set<Long>> entry : routing.kbIdsByGrayIndex().entrySet()) {
                List<FieldValue> kbs = longs(entry.getValue());
                bool.should(should -> should.bool(gray -> gray
                        .filter(filter -> filter.term(term -> term.field("_index").value(entry.getKey())))
                        .filter(filter -> filter.terms(terms -> terms.field("kbId").terms(values -> values.value(kbs))))));
            }
            return bool.minimumShouldMatch("1");
        })));
    }

    private static List<FieldValue> longs(Set<Long> values) {
        return values.stream().sorted().map(FieldValue::of).toList();
    }
}
```

- [ ] **Step 5：适配器接入**

`VectorRecallAdapter`：增加字段 `private final com.kwiki.indexing.gray.GrayReadRoutes routes;`；原构造器改为委托 `this(client, lifecycle, null)`；新增带 `@Autowired` 的三参构造器（第三参 `@org.springframework.lang.Nullable com.kwiki.indexing.gray.GrayReadRoutes routes`）。`search` 内：

```java
            com.kwiki.indexing.gray.ReadRouting routing = routes == null
                    ? com.kwiki.indexing.gray.ReadRouting.none() : routes.current();
            co.elastic.clients.elasticsearch._types.query_dsl.Query scope = EsScopeFilterBuilder.build(
                    scopeFilter, lifecycle.exclusions());
            co.elastic.clients.elasticsearch._types.query_dsl.Query knnFilter = EsReadRouting.filter(routing)
                    .map(route -> co.elastic.clients.elasticsearch._types.query_dsl.Query.of(q -> q.bool(b -> b
                            .filter(scope).filter(route))))
                    .orElse(scope);
            List<Hit<Map>> hits = client.search(request -> request
                            .index(EsReadRouting.indices(routing))
                            .size(topK)
                            .knn(knn -> knn
                                    .field("vector")
                                    .queryVector(vector)
                                    .numCandidates(topK * 10)
                                    .k(topK)
                                    .filter(knnFilter)),
                    Map.class)
                    .hits()
                    .hits();
```

`Bm25RecallAdapter`：同样的构造器改法；把 `.index(ElasticsearchIndexManager.ALIAS)` 改为 `.index(EsReadRouting.indices(routing))`，并在现有 `bool` 中追加 `EsReadRouting.filter(routing).ifPresent(bool::filter);`（`routing` 在方法开头按向量适配器的写法取得）。

`EsChunkLookup.byKey`：改为按 id 查询并附加路由过滤：

```java
            com.kwiki.indexing.gray.ReadRouting routing = routes == null
                    ? com.kwiki.indexing.gray.ReadRouting.none() : routes.current();
            List<co.elastic.clients.elasticsearch.core.search.Hit<Map>> hits = client.search(request -> request
                            .index(EsReadRouting.indices(routing))
                            .size(1)
                            .query(query -> query.bool(bool -> {
                                bool.filter(filter -> filter.ids(ids -> ids.values(childChunkKey)));
                                EsReadRouting.filter(routing).ifPresent(bool::filter);
                                return bool;
                            })),
                    Map.class).hits().hits();
            Map<?, ?> source = hits.isEmpty() ? null : hits.get(0).source();
```

其后映射 `ChunkHit` 的代码保持不变；构造器按向量适配器方式增加 `GrayReadRoutes`。

- [ ] **Step 6：运行测试**

Run：`./mvnw -q test -Dtest=GrayReadRoutesTest,EsReadRoutingTest,*RecallAdapter*,EsChunkLookup*,ConcurrentRecallService*`
Expected：全部通过（无灰度时 `indices` 仅别名、无额外过滤，原有行为不变）。

- [ ] **Step 7：提交**

```bash
git add src/main/java/com/kwiki/indexing/gray src/main/java/com/kwiki/infrastructure/elasticsearch src/test/java/com/kwiki/indexing/gray src/test/java/com/kwiki/infrastructure/elasticsearch/EsReadRoutingTest.java
git commit -m "feat(retrieval): 检索按知识库路由到已切换的灰度索引"
```

---

### Task 6：导入按知识库选择解析器

**Files:**
- Modify: `src/main/java/com/kwiki/wiki/api/WikiImportDocumentParser.java`
- Modify: `src/main/java/com/kwiki/wiki/api/WikiImportService.java`（调用处）
- Test: `src/test/java/com/kwiki/wiki/api/WikiImportDocumentParserTest.java`（新增用例）

**Interfaces:**
- Consumes：`GrayReadRoutes#switchedParserFor(long kbId)`。
- Produces：`WikiImportDocumentParser#parse(long kbId, String fileName, String contentType, byte[] content)`。

- [ ] **Step 1：新增失败用例**（按该测试文件已有方式构造 `parser`，新构造器多一个 `GrayReadRoutes` 参数）

```java
    @Test
    void 已切换灰度的知识库使用灰度解析器_其余知识库使用全局解析器() {
        com.kwiki.indexing.gray.GrayReadRoutes routes =
                org.mockito.Mockito.mock(com.kwiki.indexing.gray.GrayReadRoutes.class);
        org.mockito.Mockito.when(routes.switchedParserFor(7L)).thenReturn(java.util.Optional.of("kwiki-parse-2"));
        org.mockito.Mockito.when(routes.switchedParserFor(8L)).thenReturn(java.util.Optional.empty());
        // 以本文件既有写法构造 documentParseService、versions（全局已发布版本为 kwiki-parse-1）、readiness（ready=true）
        WikiImportDocumentParser importParser =
                new WikiImportDocumentParser(documentParseService, versions, readiness, routes);

        importParser.parse(7L, "a.pdf", "application/pdf", pdfBytes);
        org.mockito.Mockito.verify(documentParseService).parsePdfMultimodal("a.pdf", "application/pdf", pdfBytes);

        importParser.parse(8L, "b.pdf", "application/pdf", pdfBytes);
        org.mockito.Mockito.verify(documentParseService).parse(
                org.mockito.ArgumentMatchers.eq("b.pdf"), org.mockito.ArgumentMatchers.eq("application/pdf"),
                org.mockito.ArgumentMatchers.any(java.io.InputStream.class));
    }
```

- [ ] **Step 2：运行确认失败**：`./mvnw -q test -Dtest=WikiImportDocumentParserTest`，预期编译失败（无四参构造器与 `parse(long, ...)`）。

- [ ] **Step 3：实现**

`WikiImportDocumentParser`：
1. 增加字段 `private final com.kwiki.indexing.gray.GrayReadRoutes routes;`；原三参构造器改为委托 `this(parser, versions, readiness, null)`；新增 `@Autowired` 四参构造器（第四参 `@org.springframework.lang.Nullable`）。
2. 把现有 `parse(String, String, byte[])` 中「取已发布版本解析器」的表达式抽成方法：

```java
    /** 知识库属于已切换灰度 → 灰度解析器；否则 → 全局已发布版本的解析器。 */
    private String parserVersionFor(Long kbId) {
        if (kbId != null && routes != null) {
            java.util.Optional<String> gray = routes.switchedParserFor(kbId);
            if (gray.isPresent()) {
                return gray.get();
            }
        }
        SearchIndexVersionRepository repository = versions.getIfAvailable();
        return repository == null ? IndexingWorker.PARSER_VERSION
                : repository.findBySelectedTrue().map(version -> version.editableConfig().parserVersion())
                        .orElse(IndexingWorker.PARSER_VERSION);
    }
```

3. 新增 `public StructuredDocument parse(long kbId, String fileName, String contentType, byte[] content)`，方法体为原 `parse` 的方法体，只把 `selectedParser` 的赋值改为 `String selectedParser = parserVersionFor(kbId);`；原三参 `parse` 改为委托：`return parseWith(null, fileName, contentType, content);`——为避免重复，把方法体放进 `private StructuredDocument parseWith(Long kbId, ...)`，两个公开方法都委托它。

`WikiImportService.importDocument` 中调用处改为：

```java
                : importParser.parse(kbId, safeName, contentType, content);
```

- [ ] **Step 4：运行确认通过并提交**

Run：`./mvnw -q test -Dtest=WikiImportDocumentParserTest,WikiImportService*`

```bash
git add src/main/java/com/kwiki/wiki/api/WikiImportDocumentParser.java src/main/java/com/kwiki/wiki/api/WikiImportService.java src/test/java/com/kwiki/wiki/api/WikiImportDocumentParserTest.java
git commit -m "feat(wiki): 导入 PDF 时按知识库所在灰度选择解析器"
```

---

### Task 7：灰度管理接口

**Files:**
- Create: `src/main/java/com/kwiki/wiki/api/SearchIndexGrayReleaseController.java`
- Modify: `src/main/java/com/kwiki/wiki/api/SearchIndexAdminExceptionAdvice.java`（`assignableTypes` 追加新控制器）
- Test: `src/test/java/com/kwiki/wiki/api/SearchIndexGrayReleaseControllerTest.java`

**Interfaces:**
- Consumes：`GrayReleaseService` 全部公开方法；`ParserCatalog#options()`；`AdminCommandIdempotency#execute(String key, String action, Integer targetVersion, String operator, Supplier<Map<String,Object>>)`；`SearchIndexRebuildRunRepository#findFirstByVersionNumberOrderByIdDesc`；`JdbcTemplate`（双写积压计数）。
- Produces（前缀 `/api/v1/admin/search-indexes`，类级 `@PreAuthorize("hasRole('ADMIN')")`）：
  - `GET /gray-releases` → `List<GrayReleaseView>`
  - `POST /gray-releases`（body `CreateRequest{name?, parserVersion, kbIds}`）
  - `POST /gray-releases/{id}/sync`、`/switch`、`/switch-back`、`/end`
  - `GET /parsers` → `List<ParserCatalog.ParserOption>`
  - `record GrayReleaseView(long id, String name, String parserVersion, String parserLabel, int indexVersionNumber, String physicalName, String status, String lastError, String createdBy, Instant createdAt, Instant switchedAt, Instant endedAt, List<GrayRelease.Kb> kbs, Progress progress, Map<String,Boolean> allowedActions)`
  - `record Progress(Long runId, String runState, String switchState, long scanned, long succeeded, long failed, long pendingTargets)`

- [ ] **Step 1：失败测试**（`@WebMvcTest` 写法参照 `SearchIndexAdminControllerSecurityTest` 的注解与安全配置导入）

```java
package com.kwiki.wiki.api;

import com.kwiki.indexing.gray.*;
import com.kwiki.indexing.version.AdminCommandIdempotency;
import com.kwiki.indexing.version.SearchIndexRebuildRunRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SearchIndexGrayReleaseControllerTest {

    private final GrayReleaseService service = mock(GrayReleaseService.class);
    private final ParserCatalog parsers = mock(ParserCatalog.class);
    private final AdminCommandIdempotency commands = mock(AdminCommandIdempotency.class);
    private final SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
    private final org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
    private final SearchIndexGrayReleaseController controller =
            new SearchIndexGrayReleaseController(service, parsers, commands, runs, jdbc);

    private GrayRelease release(GrayReleaseStatus status) {
        return new GrayRelease(1, "pdfbox-v2 灰度 #1", "kwiki-parse-2", 4, status, null, "admin",
                Instant.EPOCH, null, null, List.of(new GrayRelease.Kb(7, "产品文档")));
    }

    @Test
    void 列表视图带显示名_物理名与按状态计算的操作() {
        when(service.list()).thenReturn(List.of(release(GrayReleaseStatus.SYNCED)));
        when(runs.findFirstByVersionNumberOrderByIdDesc(4)).thenReturn(Optional.empty());
        var views = controller.list().getData();
        assertThat(views).hasSize(1);
        var view = views.get(0);
        assertThat(view.parserLabel()).isEqualTo("pdfbox-v2");
        assertThat(view.physicalName()).isEqualTo("kwiki-chunks-v4");
        assertThat(view.allowedActions()).containsEntry("switch", true).containsEntry("switchBack", false)
                .containsEntry("sync", false).containsEntry("end", true);
    }

    @Test
    void 已切换时只能切回或结束() {
        var actions = SearchIndexGrayReleaseController.actions(GrayReleaseStatus.SWITCHED);
        assertThat(actions).isEqualTo(Map.of("sync", false, "switch", false, "switchBack", true, "end", true));
        assertThat(SearchIndexGrayReleaseController.actions(GrayReleaseStatus.ENDED))
                .isEqualTo(Map.of("sync", false, "switch", false, "switchBack", false, "end", false));
        assertThat(SearchIndexGrayReleaseController.actions(GrayReleaseStatus.CREATED))
                .containsEntry("sync", true).containsEntry("end", true);
    }

    @Test
    void 命令经幂等执行器并以灰度版本号审计() {
        when(service.find(1)).thenReturn(release(GrayReleaseStatus.SYNCED));
        when(commands.execute(eq("k1"), eq("GRAY_SWITCH"), eq(4), eq("admin"), any()))
                .thenReturn(Map.of("id", 1L, "status", "SWITCHED"));
        var user = mock(com.kwiki.auth.CurrentUser.class);
        when(user.username()).thenReturn("admin");
        var body = controller.switchTo(user, 1, "k1").getData();
        assertThat(body).containsEntry("status", "SWITCHED");
    }
}
```

> `com.kwiki.auth.CurrentUser` 以 `SearchIndexAdminController` 实际 import 的类型为准。

- [ ] **Step 2：运行确认失败**：`./mvnw -q test -Dtest=SearchIndexGrayReleaseControllerTest`，预期编译失败。

- [ ] **Step 3：实现控制器**（import 以 `SearchIndexAdminController` 的实际包为准，`CurrentUser`、`TransDTO` 同源）

```java
package com.kwiki.wiki.api;

import com.kwiki.indexing.gray.*;
import com.kwiki.indexing.version.AdminCommandIdempotency;
import com.kwiki.indexing.version.SearchIndexRebuildRun;
import com.kwiki.indexing.version.SearchIndexRebuildRunRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 索引灰度发布管理 API；类级角色校验是所有查询和命令的服务端边界。 */
@RestController
@RequestMapping("/api/v1/admin/search-indexes")
@PreAuthorize("hasRole('ADMIN')")
public class SearchIndexGrayReleaseController {

    private final GrayReleaseService service;
    private final ParserCatalog parsers;
    private final AdminCommandIdempotency commands;
    private final SearchIndexRebuildRunRepository runs;
    private final JdbcTemplate jdbc;

    public SearchIndexGrayReleaseController(GrayReleaseService service, ParserCatalog parsers,
                                            AdminCommandIdempotency commands,
                                            SearchIndexRebuildRunRepository runs, JdbcTemplate jdbc) {
        this.service = service;
        this.parsers = parsers;
        this.commands = commands;
        this.runs = runs;
        this.jdbc = jdbc;
    }

    public record CreateRequest(String name, @NotBlank String parserVersion, @NotEmpty List<Long> kbIds) { }

    public record Progress(Long runId, String runState, String switchState, long scanned, long succeeded,
                           long failed, long pendingTargets) { }

    public record GrayReleaseView(long id, String name, String parserVersion, String parserLabel,
                                  int indexVersionNumber, String physicalName, String status, String lastError,
                                  String createdBy, Instant createdAt, Instant switchedAt, Instant endedAt,
                                  List<GrayRelease.Kb> kbs, Progress progress, Map<String, Boolean> allowedActions) { }

    @GetMapping("/parsers")
    public TransDTO<List<ParserCatalog.ParserOption>> parsers() {
        return TransDTO.success(parsers.options());
    }

    @GetMapping("/gray-releases")
    public TransDTO<List<GrayReleaseView>> list() {
        return TransDTO.success(service.list().stream().map(this::view).toList());
    }

    @PostMapping("/gray-releases")
    public TransDTO<Map<String, Object>> create(@AuthenticationPrincipal CurrentUser user,
                                                @RequestHeader("Idempotency-Key") String key,
                                                @Valid @RequestBody CreateRequest request) {
        return TransDTO.success(commands.execute(key, "GRAY_CREATE", null, user.username(), () ->
                summary(service.create(request.name(), request.parserVersion(), request.kbIds(), user.username()))));
    }

    @PostMapping("/gray-releases/{id}/sync")
    public TransDTO<Map<String, Object>> sync(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                              @RequestHeader("Idempotency-Key") String key) {
        return command(user, id, key, "GRAY_SYNC", releaseId -> service.sync(releaseId, user.username()));
    }

    @PostMapping("/gray-releases/{id}/switch")
    public TransDTO<Map<String, Object>> switchTo(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                                  @RequestHeader("Idempotency-Key") String key) {
        return command(user, id, key, "GRAY_SWITCH", service::switchTo);
    }

    @PostMapping("/gray-releases/{id}/switch-back")
    public TransDTO<Map<String, Object>> switchBack(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                                    @RequestHeader("Idempotency-Key") String key) {
        return command(user, id, key, "GRAY_SWITCH_BACK", service::switchBack);
    }

    @PostMapping("/gray-releases/{id}/end")
    public TransDTO<Map<String, Object>> end(@AuthenticationPrincipal CurrentUser user, @PathVariable long id,
                                             @RequestHeader("Idempotency-Key") String key) {
        return command(user, id, key, "GRAY_END", service::end);
    }

    /** 服务端按状态计算可执行操作，前端只按此显示按钮。 */
    static Map<String, Boolean> actions(GrayReleaseStatus status) {
        return Map.of(
                "sync", status == GrayReleaseStatus.CREATED || status == GrayReleaseStatus.SYNCING,
                "switch", status == GrayReleaseStatus.SYNCED,
                "switchBack", status == GrayReleaseStatus.SWITCHED,
                "end", status != GrayReleaseStatus.ENDED);
    }

    private TransDTO<Map<String, Object>> command(CurrentUser user, long id, String key, String action,
                                                  Function<Long, GrayRelease> operation) {
        int version = service.find(id).indexVersionNumber();
        return TransDTO.success(commands.execute(key, action, version, user.username(),
                () -> summary(operation.apply(id))));
    }

    private static Map<String, Object> summary(GrayRelease release) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", release.id());
        result.put("status", release.status().name());
        result.put("indexVersionNumber", release.indexVersionNumber());
        return result;
    }

    private GrayReleaseView view(GrayRelease release) {
        int version = release.indexVersionNumber();
        SearchIndexRebuildRun run = runs.findFirstByVersionNumberOrderByIdDesc(version).orElse(null);
        Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM indexing_job_target WHERE target_version = ?"
                + " AND state IN ('PENDING','LEASED','RETRY_WAIT')", Long.class, version);
        Progress progress = new Progress(run == null ? null : run.getId(),
                run == null ? null : run.state().name(), run == null ? null : run.switchState().name(),
                run == null ? 0 : run.getResourcesScanned(), run == null ? 0 : run.getResourcesSucceeded(),
                run == null ? 0 : run.getResourcesFailed(), pending == null ? 0 : pending);
        return new GrayReleaseView(release.id(), release.name(), release.parserVersion(),
                ParserCatalog.label(release.parserVersion()), version, "kwiki-chunks-v" + version,
                release.status().name(), release.lastError(), release.createdBy(), release.createdAt(),
                release.switchedAt(), release.endedAt(), release.kbs(), progress, actions(release.status()));
    }
}
```

`SearchIndexAdminExceptionAdvice`：`@RestControllerAdvice(assignableTypes = {SearchIndexAdminController.class, SearchIndexGrayReleaseController.class})`。`ConflictException` 由全局 `BusinessException` 处理返回 409 与中文消息（确认全局异常处理对 `BusinessException` 的映射存在；若只在 advice 中处理，则在 `SearchIndexAdminExceptionAdvice` 增加 `@ExceptionHandler(ConflictException.class)` 返回 `TransDTO.failure(409, failure.getMessage())`）。

检查 `search_index_audit.action` 是否有 CHECK 约束限定取值：`grep -n "action" src/main/resources/db/migration/V24__search_index_audit.sql`。若有，则需在 V33 迁移中扩展约束（在 Task 1 的迁移文件末尾追加 `ALTER TABLE search_index_audit DROP CHECK ...; ALTER TABLE ... ADD CONSTRAINT ...`，并同步修改 Task 1 的契约测试）；若无（当前为 `VARCHAR(40)` 无约束），无需处理。

- [ ] **Step 4：运行测试并提交**

Run：`./mvnw -q test -Dtest=SearchIndexGrayReleaseControllerTest,SearchIndexAdminControllerSecurityTest`

```bash
git add src/main/java/com/kwiki/wiki/api src/test/java/com/kwiki/wiki/api/SearchIndexGrayReleaseControllerTest.java
git commit -m "feat(admin-api): 新增索引灰度发布管理接口"
```

---

### Task 8：管理端「灰度发布」页

**Files:**
- Create: `admin-frontend/src/parsers.ts`
- Modify: `admin-frontend/src/api.ts`
- Create: `admin-frontend/src/components/GrayReleaseCard.vue`、`admin-frontend/src/components/GrayReleaseCreateDialog.vue`、`admin-frontend/src/views/GrayReleaseView.vue`
- Modify: `admin-frontend/src/router.ts`、`admin-frontend/src/components/AdminLayout.vue`
- Test: `admin-frontend/tests/gray-release.spec.ts`

**Interfaces:**
- Consumes：Task 7 的接口与视图字段（字段名逐一对应 `GrayReleaseView` / `Progress` / `ParserOption`）。
- Produces：路由 `/gray-releases`；导航项「灰度发布」；`parserLabel(id: string): string`。

- [ ] **Step 1：`parsers.ts`**

```ts
/** 解析器显示名：与后端 ParserCatalog 一致，接口与存储仍使用原标识。 */
const LABELS: Record<string, string> = { "kwiki-parse-1": "tika-v1", "kwiki-parse-2": "pdfbox-v2" };

export function parserLabel(id: string): string {
  return LABELS[id] ?? id;
}
```

- [ ] **Step 2：`api.ts` 追加类型与方法**（保持该文件的紧凑风格，追加在 `export const api={` 之前的类型区与 `api` 对象内）

```ts
export interface ParserOption{id:string;label:string;available:boolean;unavailableReason:string|null}
export interface GrayProgress{runId:number|null;runState:string|null;switchState:string|null;scanned:number;succeeded:number;failed:number;pendingTargets:number}
export interface GrayRelease{id:number;name:string;parserVersion:string;parserLabel:string;indexVersionNumber:number;physicalName:string;status:"CREATED"|"SYNCING"|"SYNCED"|"SWITCHED"|"ENDED";lastError:string|null;createdBy:string;createdAt:string;switchedAt:string|null;endedAt:string|null;kbs:{kbId:number;name:string}[];progress:GrayProgress;allowedActions:{sync:boolean;switch:boolean;switchBack:boolean;end:boolean}}
```

`api` 对象内追加：

```ts
  parsers:()=>request<ParserOption[]>("/admin/search-indexes/parsers"),
  grayReleases:()=>request<GrayRelease[]>("/admin/search-indexes/gray-releases"),
  createGrayRelease:(body:{name?:string;parserVersion:string;kbIds:number[]})=>request<{id:number;status:string}>("/admin/search-indexes/gray-releases",{method:"POST",headers:{"Idempotency-Key":key()},body:JSON.stringify(body)}),
  grayCommand:(id:number,action:"sync"|"switch"|"switch-back"|"end")=>request<{id:number;status:string}>(`/admin/search-indexes/gray-releases/${id}/${action}`,{method:"POST",headers:{"Idempotency-Key":key()}}),
```

- [ ] **Step 3：写失败测试 `tests/gray-release.spec.ts`**

```ts
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import type { GrayRelease } from "../src/api";

const mocks = vi.hoisted(() => ({ grayReleases: vi.fn(), parsers: vi.fn(), adminKnowledgeBases: vi.fn(), grayCommand: vi.fn(), createGrayRelease: vi.fn() }));
vi.mock("../src/api", async original => {
  const actual = await original<typeof import("../src/api")>();
  return { ...actual, api: { ...actual.api, ...mocks } };
});
import GrayReleaseView from "../src/views/GrayReleaseView.vue";
import GrayReleaseCreateDialog from "../src/components/GrayReleaseCreateDialog.vue";

const base: GrayRelease = {
  id: 1, name: "pdfbox-v2 灰度 #1", parserVersion: "kwiki-parse-2", parserLabel: "pdfbox-v2", indexVersionNumber: 4,
  physicalName: "kwiki-chunks-v4", status: "SYNCED", lastError: null, createdBy: "admin", createdAt: "2026-09-27T00:00:00Z",
  switchedAt: null, endedAt: null, kbs: [{ kbId: 7, name: "产品文档" }],
  progress: { runId: 9, runState: "COMPLETED", switchState: "READY", scanned: 10, succeeded: 10, failed: 0, pendingTargets: 0 },
  allowedActions: { sync: false, switch: true, switchBack: false, end: true },
};
const plugins = [createPinia(), ElementPlus];

describe("灰度发布页", () => {
  it("按 allowedActions 显示操作按钮，并展示解析器、知识库与进度", async () => {
    mocks.grayReleases.mockResolvedValue([base]);
    render(GrayReleaseView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("pdfbox-v2 灰度 #1")).toBeTruthy());
    expect(screen.getByText("产品文档")).toBeTruthy();
    expect(screen.getByText("kwiki-chunks-v4")).toBeTruthy();
    expect(screen.getByRole("button", { name: "切换到灰度索引" })).toBeTruthy();
    expect(screen.queryByRole("button", { name: "切回原索引" })).toBeNull();
    expect(screen.queryByRole("button", { name: "开始同步" })).toBeNull();
  });

  it("失败原因醒目展示", async () => {
    mocks.grayReleases.mockResolvedValue([{ ...base, status: "SYNCING", lastError: "校验未通过：missingResources=3", allowedActions: { sync: true, switch: false, switchBack: false, end: true } }]);
    render(GrayReleaseView, { global: { plugins } });
    await waitFor(() => expect(screen.getByText("校验未通过：missingResources=3")).toBeTruthy());
    expect(screen.getByRole("button", { name: "开始同步" })).toBeTruthy();
  });
});

describe("新建灰度弹窗", () => {
  it("搜索知识库；已在其他灰度中的知识库置灰；不可用解析器置灰", async () => {
    mocks.parsers.mockResolvedValue([
      { id: "kwiki-parse-1", label: "tika-v1", available: true, unavailableReason: null },
      { id: "kwiki-parse-2", label: "pdfbox-v2", available: false, unavailableReason: "缺少配置：vision" },
    ]);
    mocks.adminKnowledgeBases.mockResolvedValue([{ id: 7, name: "产品文档" }, { id: 8, name: "技术规范" }, { id: 9, name: "运维手册" }]);
    render(GrayReleaseCreateDialog, { props: { modelValue: true, occupied: { 8: "pdfbox-v2 灰度 #1" } }, global: { plugins } });
    await waitFor(() => expect(screen.getByText("运维手册")).toBeTruthy());
    expect((screen.getByRole("radio", { name: /pdfbox-v2/ }) as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText("缺少配置：vision")).toBeTruthy();
    expect((screen.getByRole("checkbox", { name: /技术规范/ }) as HTMLInputElement).disabled).toBe(true);
    await fireEvent.update(screen.getByPlaceholderText("搜索知识库"), "运维");
    expect(screen.queryByText("产品文档")).toBeNull();
    expect(screen.getByText("运维手册")).toBeTruthy();
  });
});
```

- [ ] **Step 4：运行确认失败**：`cd admin-frontend && npx vitest run tests/gray-release.spec.ts`，预期组件不存在导致失败。

- [ ] **Step 5：`GrayReleaseCreateDialog.vue`**

```vue
<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { ElMessage } from "element-plus";
import { api, type ParserOption } from "../api";

/** occupied：已在其他未结束灰度中的知识库 → 所在灰度名称 */
const props = defineProps<{ modelValue: boolean; occupied: Record<number, string> }>();
const emit = defineEmits<{ "update:modelValue": [value: boolean]; created: [] }>();

const parsers = ref<ParserOption[]>([]);
const bases = ref<{ id: number; name: string }[]>([]);
const parserVersion = ref("");
const selected = ref<number[]>([]);
const name = ref("");
const keyword = ref("");
const saving = ref(false);

const filtered = computed(() => {
  const word = keyword.value.trim().toLowerCase();
  return word ? bases.value.filter(base => base.name.toLowerCase().includes(word) || String(base.id) === word) : bases.value;
});

async function load() {
  [parsers.value, bases.value] = await Promise.all([api.parsers(), api.adminKnowledgeBases()]);
  parserVersion.value = parsers.value.find(parser => parser.available && parser.id !== "kwiki-parse-1")?.id ?? "";
  selected.value = [];
  name.value = "";
  keyword.value = "";
}

watch(() => props.modelValue, open => { if (open) void load(); }, { immediate: true });

async function submit() {
  saving.value = true;
  try {
    await api.createGrayRelease({ name: name.value.trim() || undefined, parserVersion: parserVersion.value, kbIds: selected.value });
    ElMessage.success("灰度已创建，点击「开始同步」构建灰度索引");
    emit("update:modelValue", false);
    emit("created");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "创建失败");
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <el-dialog :model-value="modelValue" title="新建灰度" width="620" @update:model-value="emit('update:modelValue', $event)">
    <el-form label-position="top">
      <el-form-item label="解析器">
        <el-radio-group v-model="parserVersion" class="parser-group">
          <el-radio v-for="parser in parsers" :key="parser.id" :value="parser.id" :disabled="!parser.available" border>
            {{ parser.label }}<span class="parser-id">{{ parser.id }}</span>
          </el-radio>
        </el-radio-group>
        <p v-for="parser in parsers.filter(item => !item.available)" :key="parser.id" class="hint">{{ parser.unavailableReason }}</p>
      </el-form-item>
      <el-form-item :label="`知识库（已选 ${selected.length}）`">
        <el-input v-model="keyword" placeholder="搜索知识库" clearable />
        <el-checkbox-group v-model="selected" class="kb-list">
          <el-checkbox v-for="base in filtered" :key="base.id" :value="base.id" :disabled="Boolean(occupied[base.id])">
            {{ base.name }}<span v-if="occupied[base.id]" class="occupied">已在「{{ occupied[base.id] }}」中</span>
          </el-checkbox>
          <p v-if="!filtered.length" class="hint">没有匹配的知识库</p>
        </el-checkbox-group>
      </el-form-item>
      <el-form-item label="名称（可选）">
        <el-input v-model="name" placeholder="默认：解析器名 + 灰度编号" maxlength="120" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="emit('update:modelValue', false)">取消</el-button>
      <el-button type="primary" :loading="saving" :disabled="!parserVersion || !selected.length" @click="submit">创建灰度</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.parser-group { display: flex; gap: 8px; flex-wrap: wrap; }
.parser-id { margin-left: 6px; color: var(--k-faint); font-size: 12px; }
.kb-list { display: grid; gap: 2px; width: 100%; max-height: 280px; margin-top: 8px; padding: 6px 10px; overflow: auto; border: 1px solid var(--k-line); border-radius: var(--k-r); }
.occupied { margin-left: 8px; color: var(--k-muted); font-size: 12px; }
.hint { margin: 4px 0 0; color: var(--k-muted); font-size: 12px; }
</style>
```

- [ ] **Step 6：`GrayReleaseCard.vue`**

```vue
<script setup lang="ts">
import { computed } from "vue";
import { ElMessageBox } from "element-plus";
import type { GrayRelease } from "../api";

const props = defineProps<{ release: GrayRelease; busy: boolean }>();
const emit = defineEmits<{ command: [action: "sync" | "switch" | "switch-back" | "end"] }>();

const STATUS: Record<GrayRelease["status"], { text: string; type: "info" | "warning" | "success" | "primary" | "danger" }> = {
  CREATED: { text: "待同步", type: "info" },
  SYNCING: { text: "同步中", type: "warning" },
  SYNCED: { text: "已同步，未切换", type: "primary" },
  SWITCHED: { text: "已切换到灰度", type: "success" },
  ENDED: { text: "已结束", type: "info" },
};
const status = computed(() => STATUS[props.release.status]);
const progressText = computed(() => {
  const p = props.release.progress;
  if (!p.runId) return "尚未同步";
  return `已处理 ${p.succeeded}/${p.scanned}，失败 ${p.failed}，双写积压 ${p.pendingTargets}`;
});

const CONFIRM: Record<"switch" | "switch-back" | "end", string> = {
  switch: "这些知识库的检索将立即改为读取灰度索引，旧索引继续双写保鲜，可随时切回。",
  "switch-back": "这些知识库的检索将立即改回读取全局索引，灰度索引继续双写，可再次切换。",
  end: "这些知识库将回到全局索引，并停止向灰度索引写入。灰度索引会保留，需要时在「全局索引」页手动删除。",
};

async function run(action: "sync" | "switch" | "switch-back" | "end") {
  if (action !== "sync") {
    try { await ElMessageBox.confirm(CONFIRM[action], "确认操作", { type: "warning" }); } catch { return; }
  }
  emit("command", action);
}
</script>

<template>
  <article class="gray-card">
    <header>
      <div>
        <h3>{{ release.name }}</h3>
        <p class="meta">解析器 <strong>{{ release.parserLabel }}</strong> · 索引 <code>{{ release.physicalName }}</code> · {{ release.createdBy }}</p>
      </div>
      <el-tag :type="status.type" effect="light">{{ status.text }}</el-tag>
    </header>
    <div class="kbs"><el-tag v-for="kb in release.kbs" :key="kb.kbId" effect="plain" size="small">{{ kb.name }}</el-tag></div>
    <p class="progress">{{ progressText }}</p>
    <el-alert v-if="release.lastError" :title="release.lastError" type="error" :closable="false" show-icon />
    <footer>
      <el-button v-if="release.allowedActions.sync" :loading="busy" @click="run('sync')">开始同步</el-button>
      <el-button v-if="release.allowedActions.switch" type="primary" :loading="busy" @click="run('switch')">切换到灰度索引</el-button>
      <el-button v-if="release.allowedActions.switchBack" :loading="busy" @click="run('switch-back')">切回原索引</el-button>
      <el-button v-if="release.allowedActions.end" text type="danger" :loading="busy" @click="run('end')">结束灰度</el-button>
    </footer>
  </article>
</template>

<style scoped>
.gray-card { display: grid; gap: 12px; padding: 18px 20px; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas); }
header { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; }
h3 { margin: 0; font-size: 16px; font-weight: 600; letter-spacing: -0.2px; }
.meta { margin: 4px 0 0; color: var(--k-muted); font-size: 13px; }
.meta strong { color: var(--k-ink); }
code { font-family: var(--k-font-mono); font-size: 12px; }
.kbs { display: flex; flex-wrap: wrap; gap: 6px; }
.progress { margin: 0; color: var(--k-ink-2); font-size: 13px; font-variant-numeric: tabular-nums; }
footer { display: flex; gap: 8px; flex-wrap: wrap; }
</style>
```

- [ ] **Step 7：`GrayReleaseView.vue`**

```vue
<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import { api, type GrayRelease } from "../api";
import GrayReleaseCard from "../components/GrayReleaseCard.vue";
import GrayReleaseCreateDialog from "../components/GrayReleaseCreateDialog.vue";

const releases = ref<GrayRelease[]>([]);
const loading = ref(false);
const creating = ref(false);
const busyId = ref<number | null>(null);
const showEnded = ref(false);
let timer: number | undefined;

const active = computed(() => releases.value.filter(release => release.status !== "ENDED"));
const ended = computed(() => releases.value.filter(release => release.status === "ENDED"));
/** 已在未结束灰度中的知识库 → 灰度名称，供新建弹窗置灰 */
const occupied = computed(() => Object.fromEntries(active.value.flatMap(release => release.kbs.map(kb => [kb.kbId, release.name]))));

async function load(silent = false) {
  if (!silent) loading.value = true;
  try { releases.value = await api.grayReleases(); } catch { if (!silent) ElMessage.error("灰度列表加载失败"); }
  finally { loading.value = false; schedule(); }
}

/** 有同步中的灰度时每 3 秒刷新，否则每 15 秒 */
function schedule() {
  window.clearTimeout(timer);
  timer = window.setTimeout(() => load(true), releases.value.some(release => release.status === "SYNCING") ? 3000 : 15000);
}

async function command(release: GrayRelease, action: "sync" | "switch" | "switch-back" | "end") {
  busyId.value = release.id;
  try { await api.grayCommand(release.id, action); await load(true); }
  catch (error) { ElMessage.error(error instanceof Error ? error.message : "操作失败"); }
  finally { busyId.value = null; }
}

onMounted(() => load());
onBeforeUnmount(() => window.clearTimeout(timer));
</script>

<template>
  <main class="page" v-loading="loading">
    <section class="hero">
      <div>
        <h1>灰度发布</h1>
        <p class="muted">选择部分知识库试用新的解析器：系统会为它们建立独立的灰度索引。同步完成后由你手动切换，旧索引持续双写保鲜，可随时切回。</p>
      </div>
      <el-button type="primary" @click="creating = true">新建灰度</el-button>
    </section>
    <el-empty v-if="!loading && !active.length" description="暂无进行中的灰度" />
    <div class="grid"><GrayReleaseCard v-for="release in active" :key="release.id" :release="release" :busy="busyId === release.id" @command="command(release, $event)" /></div>
    <section v-if="ended.length" class="ended">
      <el-button text @click="showEnded = !showEnded">{{ showEnded ? "收起" : "查看" }}已结束的灰度（{{ ended.length }}）</el-button>
      <div v-if="showEnded" class="grid"><GrayReleaseCard v-for="release in ended" :key="release.id" :release="release" :busy="false" /></div>
    </section>
    <GrayReleaseCreateDialog v-model="creating" :occupied="occupied" @created="load(true)" />
  </main>
</template>

<style scoped>
.grid { display: grid; gap: 14px; }
.ended { margin-top: 24px; }
</style>
```

- [ ] **Step 8：路由与导航**

`router.ts` 的 `routes` 数组中 `/knowledge-graphs` 之前加入 `{path:"/gray-releases",component:GrayReleaseView}`，并 `import GrayReleaseView from "./views/GrayReleaseView.vue";`（保持该文件单行紧凑风格）。

`AdminLayout.vue` 的 `<nav>`：把第一项文字「索引管理」改为「全局索引」，并在它之后插入：

```vue
        <RouterLink to="/gray-releases" class="nav-item" active-class="active">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 18h4v-6H4zM10 18h4V6h-4zM16 18h4v-9h-4z" /></svg>灰度发布
        </RouterLink>
```

同步修改 `tests/admin-layout.spec.ts` 中对「索引管理」的断言为「全局索引」。

- [ ] **Step 9：运行测试并提交**

Run：`cd admin-frontend && npx vue-tsc --noEmit && npx vitest run && npx vite build`
Expected：全部通过。

```bash
git add admin-frontend
git commit -m "feat(admin): 新增灰度发布页：新建灰度、同步、切换、切回与结束"
```

---

### Task 9：全局索引页顶部卡片与切换

**Files:**
- Create: `admin-frontend/src/components/CurrentIndexCards.vue`
- Modify: `admin-frontend/src/views/IndexManagementView.vue`（在 `.hero` 之后挂载）
- Test: `admin-frontend/tests/current-index-cards.spec.ts`

**Interfaces:**
- Consumes：`api.versions()`（`Version` 含 `selected`、`physicalName`、`configuration.parserVersion`、`allowedActions.select`、`kbScoped`）、`api.alias()`、`api.command(\`/versions/${n}/select\`)`；`parserLabel`。
- Produces：组件事件 `switched`（切换成功后父页面刷新）。

- [ ] **Step 1：`api.ts` 的 `Version` 类型追加字段 `kbScoped:boolean`。**

- [ ] **Step 2：失败测试 `tests/current-index-cards.spec.ts`**

```ts
import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/vue";
import ElementPlus from "element-plus";
import CurrentIndexCards from "../src/components/CurrentIndexCards.vue";
import type { Version } from "../src/api";

const version = (n: number, parser: string, selected: boolean, canSelect: boolean, kbScoped = false) => ({
  versionNumber: n, physicalName: `kwiki-chunks-v${n}`, selected, kbScoped,
  configuration: { parserVersion: parser, chunkerVersion: "c", embeddingProvider: "d", embeddingModel: "m", embeddingDimensions: 1024, mappingSchemaVersion: 3 },
  allowedActions: { select: canSelect },
}) as unknown as Version;

describe("全局索引顶部卡片", () => {
  it("一眼展示当前别名指向与解析器显示名", () => {
    render(CurrentIndexCards, { props: { versions: [version(1, "kwiki-parse-1", true, false)], aliasTargets: ["kwiki-chunks-v1"] }, global: { plugins: [ElementPlus] } });
    expect(screen.getByText("kwiki-chunks-v1")).toBeTruthy();
    expect(screen.getByText("tika-v1")).toBeTruthy();
  });

  it("点击卡片列出可发布的全局版本，排除灰度版本，并说明解析器随版本切换", async () => {
    render(CurrentIndexCards, {
      props: { versions: [version(1, "kwiki-parse-1", true, false), version(2, "kwiki-parse-2", false, true), version(3, "kwiki-parse-2", false, false, true)], aliasTargets: ["kwiki-chunks-v1"] },
      global: { plugins: [ElementPlus] },
    });
    await fireEvent.click(screen.getByRole("button", { name: /当前解析器/ }));
    await waitFor(() => expect(screen.getByText(/解析器随索引版本一起切换/)).toBeTruthy());
    expect(screen.getByRole("radio", { name: /kwiki-chunks-v2/ })).toBeTruthy();
    expect(screen.queryByRole("radio", { name: /kwiki-chunks-v3/ })).toBeNull();
  });
});
```

- [ ] **Step 3：运行确认失败**：`cd admin-frontend && npx vitest run tests/current-index-cards.spec.ts`。

- [ ] **Step 4：`CurrentIndexCards.vue`**

```vue
<script setup lang="ts">
import { computed, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import { api, type Version } from "../api";
import { parserLabel } from "../parsers";

const props = defineProps<{ versions: Version[]; aliasTargets: string[] }>();
const emit = defineEmits<{ switched: [] }>();

const current = computed(() => props.versions.find(version => version.selected));
/** 可发布为全局的版本：服务端允许 select 且不是灰度版本 */
const candidates = computed(() => props.versions.filter(version => version.allowedActions.select && !version.kbScoped));
const open = ref(false);
const target = ref<number | null>(null);
const saving = ref(false);

function openDialog() {
  target.value = candidates.value[0]?.versionNumber ?? null;
  open.value = true;
}

async function confirm() {
  const chosen = candidates.value.find(version => version.versionNumber === target.value);
  if (!chosen) return;
  try {
    await ElMessageBox.confirm(`线上检索将原子切换到 ${chosen.physicalName}，解析器变为 ${parserLabel(chosen.configuration.parserVersion)}。`, "确认切换全局索引", { type: "warning" });
  } catch { return; }
  saving.value = true;
  try {
    await api.command(`/versions/${chosen.versionNumber}/select`);
    ElMessage.success("已切换");
    open.value = false;
    emit("switched");
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : "切换失败");
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <section class="current-cards">
    <button type="button" class="current-card" aria-label="当前别名，点击切换" @click="openDialog">
      <span class="label">当前别名</span>
      <strong>{{ aliasTargets.join(", ") || "未指向任何索引" }}</strong>
      <span class="sub">kwiki-chunks → 线上读请求实际命中的物理索引</span>
      <span class="action">切换</span>
    </button>
    <button type="button" class="current-card" aria-label="当前解析器，点击切换" @click="openDialog">
      <span class="label">当前解析器</span>
      <strong>{{ current ? parserLabel(current.configuration.parserVersion) : "—" }}</strong>
      <span class="sub">{{ current?.configuration.parserVersion }} · 新导入的 PDF 与全局索引使用该解析器</span>
      <span class="action">切换</span>
    </button>
    <el-dialog v-model="open" title="切换全局索引" width="560">
      <p class="note">解析器随索引版本一起切换：选择一个已同步、已校验的版本，线上检索与新导入都会改用它。只想让部分知识库试用新解析器，请使用「灰度发布」。</p>
      <el-radio-group v-if="candidates.length" v-model="target" class="choices">
        <el-radio v-for="version in candidates" :key="version.versionNumber" :value="version.versionNumber" border>
          {{ version.physicalName }} · {{ parserLabel(version.configuration.parserVersion) }}
        </el-radio>
      </el-radio-group>
      <el-empty v-else description="暂无可发布的版本：请先在下方新建版本并完成重建、补齐与校验" />
      <template #footer>
        <el-button @click="open = false">取消</el-button>
        <el-button type="primary" :disabled="!target" :loading="saving" @click="confirm">确认切换</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.current-cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; margin-bottom: 24px; }
.current-card { position: relative; display: grid; gap: 6px; padding: 18px 20px; border: 1px solid var(--k-line); border-radius: var(--k-r-lg); background: var(--k-canvas); color: var(--k-ink); font: inherit; text-align: left; cursor: pointer; transition: border-color var(--k-ease), box-shadow var(--k-ease); }
.current-card:hover { border-color: var(--k-line-strong); box-shadow: var(--k-shadow); }
.label { color: var(--k-muted); font-size: 12px; font-weight: 500; }
strong { font-family: var(--k-font-mono); font-size: 22px; font-weight: 600; letter-spacing: -0.4px; }
.sub { color: var(--k-muted); font-size: 12px; }
.action { position: absolute; top: 16px; right: 18px; color: var(--k-green-deep); font-size: 13px; font-weight: 500; }
.note { margin: 0 0 14px; color: var(--k-ink-2); line-height: 1.6; }
.choices { display: grid; gap: 8px; }
@media (max-width: 900px) { .current-cards { grid-template-columns: 1fr; } }
</style>
```

- [ ] **Step 5：挂载到 `IndexManagementView.vue`**

在 `<script setup>` 中 `import CurrentIndexCards from "../components/CurrentIndexCards.vue";`；模板中 `</section>`（`.hero` 结束）之后插入：

```vue
      <CurrentIndexCards :versions="versions" :alias-targets="alias" @switched="load()" />
```

并删除原「当前别名」指标卡（它已被顶部卡片取代），保留其余指标卡。`versions` / `alias` / `load` 以该文件现有变量名为准。

- [ ] **Step 6：运行测试并提交**

Run：`cd admin-frontend && npx vue-tsc --noEmit && npx vitest run && npx vite build`

```bash
git add admin-frontend
git commit -m "feat(admin): 全局索引页顶部展示并可切换当前别名与解析器"
```

---

### Task 10：整体验收

- [ ] **Step 1：后端全量测试**：`./mvnw -q test`，全部通过（外部契约测试无环境变量时跳过）。若本机有 `KWIKI_IT_MYSQL_URL`，额外运行 `./mvnw -q test -Dtest=GrayReleaseMigrationContractTest`。
- [ ] **Step 2：离线边界检查**：`scripts/test-offline.sh`。
- [ ] **Step 3：管理端**：`cd admin-frontend && npx vue-tsc --noEmit && npx vitest run && npx vite build`。
- [ ] **Step 4：端到端演练（本地环境，由用户确认可操作后进行）**：
  1. 全局索引页顶部两张卡片显示 `kwiki-chunks-v1` 与 `tika-v1`。
  2. 灰度发布页新建灰度：选 1 个测试知识库 + `pdfbox-v2`（若未就绪则置灰，选择可用解析器演练流程）。
  3. 开始同步 → 状态「同步中」，进度数字递增 → 自动进入「已同步，未切换」。
  4. 切换 → 在该知识库发起检索/聊天，确认命中灰度索引（可通过 ES `_index` 字段或日志确认）；在其他知识库检索不受影响。
  5. 在该知识库导入一个 PDF，确认使用灰度解析器。
  6. 切回 → 检索恢复读全局索引，数据是最新的（切换期间新增内容可被检索到）。
  7. 结束灰度 → 知识库回到全局；全局页版本列表中灰度版本显示为可清理，手动删除成功。
- [ ] **Step 5：提交修补（如有）**：`git commit -m "fix(indexing): 修补灰度发布验收中发现的问题"`。
