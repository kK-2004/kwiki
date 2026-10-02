package com.kwiki.indexing.gray;

import com.kwiki.indexing.config.IndexingProperties;
import com.kwiki.indexing.version.*;
import com.kwiki.wiki.api.ConflictException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 灰度发布状态机：创建（建限定范围的索引版本）→ 同步（开启双写 → 存量迁移 → 校验，由 advance 推进）
 * → 切换 / 切回 → 结束（关闭灰度版本写入，索引保留待手动删除）。
 * 存量迁移只在双写开启后发起，截止到双写起点，保证历史与增量之间没有缺口。
 */
@Service
public class GrayReleaseService {

    private final GrayReleaseStore store;
    private final SearchIndexAdminService admin;
    private final SearchIndexVersionRepository versions;
    private final IndexVersionKbScope scope;
    private final IndexVersionWriteService writes;
    private final IndexMigrationService migrations;
    private final SearchIndexValidationService validations;
    private final SearchIndexRebuildRunRepository runs;
    private final ParserCatalog parsers;
    /** 管理写操作开关；为 null（离线测试）时不检查。 */
    private final IndexingProperties properties;

    public GrayReleaseService(GrayReleaseStore store, SearchIndexAdminService admin,
                              SearchIndexVersionRepository versions, IndexVersionKbScope scope,
                              IndexVersionWriteService writes, IndexMigrationService migrations,
                              SearchIndexValidationService validations,
                              SearchIndexRebuildRunRepository runs, ParserCatalog parsers) {
        this(store, admin, versions, scope, writes, migrations, validations, runs, parsers, null);
    }

    @Autowired
    public GrayReleaseService(GrayReleaseStore store, SearchIndexAdminService admin,
                              SearchIndexVersionRepository versions, IndexVersionKbScope scope,
                              IndexVersionWriteService writes, IndexMigrationService migrations,
                              SearchIndexValidationService validations,
                              SearchIndexRebuildRunRepository runs, ParserCatalog parsers,
                              IndexingProperties properties) {
        this.properties = properties;
        this.store = store;
        this.admin = admin;
        this.versions = versions;
        this.scope = scope;
        this.writes = writes;
        this.migrations = migrations;
        this.validations = validations;
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
        requireMutationsEnabled();
        List<Long> distinct = kbIds == null ? List.of() : kbIds.stream().distinct().toList();
        if (distinct.isEmpty()) {
            throw new ConflictException("请至少选择一个知识库");
        }
        if (name != null && name.trim().length() > 120) {
            throw new ConflictException("名称不能超过 120 个字符");
        }
        parsers.requireAvailable(parserVersion);
        Map<Long, Long> conflicts = store.activeReleaseByKb(distinct);
        if (!conflicts.isEmpty()) {
            throw new ConflictException("以下知识库已在其他灰度中：" + conflicts.keySet());
        }
        EditableIndexConfig base = versions.findBySelectedTrue()
                .orElseThrow(() -> new ConflictException("尚无已发布的全局索引版本，无法创建灰度"))
                .editableConfig();
        EditableIndexConfig config = parsers.configFor(parserVersion, base).orElseThrow(() -> new ConflictException(
                "当前部署没有为 " + ParserCatalog.label(parserVersion) + " 登记与全局版本（模型 " + base.embeddingModel()
                        + "，" + base.embeddingDimensions() + " 维）兼容的索引结构清单，请在 kwiki.indexing.manifests 中登记"));
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
                // 迁移名额或版本锁暂被占用（BUSY）时不记失败，下一轮自动重试
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
        if (!startMigration(number, operator)) {
            // 灰度已进入同步中且无失败原因，同步驱动器会在下一轮自动重新发起
            throw new ConflictException("另一个索引迁移正在进行，系统会在空闲后自动开始存量迁移");
        }
    }

    /** 返回 false 表示准入繁忙（BUSY），属于暂时状态；其他失败以异常抛出。 */
    private boolean startMigration(int number, String operator) {
        VersionRebuildCoordinator.StartResult result = migrations.migrate(number, operator);
        return result != null && result.accepted();
    }

    /**
     * 校验报告失效时回到同步中的状态更新必须保留，故 ConflictException 不回滚事务。
     * 回到同步中时不写 lastError，以便 advance 下一轮自动重新校验。
     */
    @Transactional(noRollbackFor = ConflictException.class)
    public GrayRelease switchTo(long id) {
        requireMutationsEnabled();
        GrayRelease release = find(id);
        if (release.status() != GrayReleaseStatus.SYNCED) {
            throw new ConflictException("只有同步完成的灰度才能切换");
        }
        parsers.requireAvailable(release.parserVersion());
        if (validations.currentReadyReport(release.indexVersionNumber()).isEmpty()) {
            requireTransition(store.transition(id, GrayReleaseStatus.SYNCED, GrayReleaseStatus.SYNCING, null));
            throw new ConflictException("校验报告已失效，已重新进入同步，系统会自动重新校验，请稍后再切换");
        }
        requireTransition(store.markSwitched(id));
        return find(id);
    }

    @Transactional
    public GrayRelease switchBack(long id) {
        requireMutationsEnabled();
        GrayRelease release = find(id);
        if (release.status() != GrayReleaseStatus.SWITCHED) {
            throw new ConflictException("灰度未处于已切换状态");
        }
        requireTransition(store.transition(id, GrayReleaseStatus.SWITCHED, GrayReleaseStatus.SYNCED, null));
        return find(id);
    }

    @Transactional
    public GrayRelease end(long id) {
        requireMutationsEnabled();
        GrayRelease release = find(id);
        if (release.status() == GrayReleaseStatus.ENDED) {
            throw new ConflictException("灰度已结束");
        }
        int number = release.indexVersionNumber();
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
        requireTransition(store.end(id, release.status()));
        return find(id);
    }

    private void requireMutationsEnabled() {
        if (properties != null && !Boolean.TRUE.equals(properties.management().mutationsEnabled())) {
            throw new ConflictException("索引管理写操作已被关闭（kwiki.indexing.management.mutations-enabled）");
        }
    }

    private static void requireTransition(boolean updated) {
        if (!updated) {
            throw new ConflictException("灰度状态已变化，请刷新后重试");
        }
    }

    private static String safe(String value) {
        if (value == null || value.isBlank()) {
            return "未知原因";
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }
}
