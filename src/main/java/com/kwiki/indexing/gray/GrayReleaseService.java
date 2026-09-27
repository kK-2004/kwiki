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
    /** 管理写操作开关；为 null（离线测试）时不检查。 */
    private final IndexingProperties properties;

    public GrayReleaseService(GrayReleaseStore store, SearchIndexAdminService admin,
                              SearchIndexVersionRepository versions, IndexVersionKbScope scope,
                              ManualIndexRebuildService rebuilds, SwitchPreparationService preparations,
                              SearchIndexValidationService validations, IndexVersionEnablementService enablement,
                              SearchIndexRebuildRunRepository runs, ParserCatalog parsers) {
        this(store, admin, versions, scope, rebuilds, preparations, validations, enablement, runs, parsers, null);
    }

    @Autowired
    public GrayReleaseService(GrayReleaseStore store, SearchIndexAdminService admin,
                              SearchIndexVersionRepository versions, IndexVersionKbScope scope,
                              ManualIndexRebuildService rebuilds, SwitchPreparationService preparations,
                              SearchIndexValidationService validations, IndexVersionEnablementService enablement,
                              SearchIndexRebuildRunRepository runs, ParserCatalog parsers,
                              IndexingProperties properties) {
        this.properties = properties;
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
        requireMutationsEnabled();
        GrayRelease release = find(id);
        if (release.status() != GrayReleaseStatus.CREATED && release.status() != GrayReleaseStatus.SYNCING) {
            throw new ConflictException("当前状态不能开始同步：" + release.status());
        }
        SearchIndexVersion version = versions.findByVersionNumber(release.indexVersionNumber())
                .orElseThrow(() -> new ConflictException("灰度索引版本不存在"));
        // 以最新重建记录判断：当前配置已重建完成且已进入（或完成）切换准备时，只需清除失败原因重新推进校验；
        // 不能以写入是否开启判断，写入可能被其他路径开启而索引并未构建。
        SearchIndexRebuildRun run = runs.findFirstByVersionNumberOrderByIdDesc(release.indexVersionNumber())
                .orElse(null);
        boolean builtAndPrepared = run != null
                && run.state() == RebuildRunState.COMPLETED
                && run.getConfigRevision() == version.getConfigRevision()
                && (run.switchState() == IndexSwitchState.READY || run.switchState() == IndexSwitchState.PREPARING);
        if (!builtAndPrepared) {
            if (version.isWriteEnabled()) {
                throw new ConflictException("灰度索引版本写入已开启但尚未完成构建，无法重新同步，请结束该灰度后重新创建");
            }
            VersionRebuildCoordinator.StartResult result = rebuilds.rebuild(release.indexVersionNumber(), operator);
            if (result == null || !result.accepted()) {
                throw new ConflictException("另一个索引重建正在进行，请稍后再开始同步");
            }
        }
        requireTransition(store.transition(id, release.status(), GrayReleaseStatus.SYNCING, null));
        return find(id);
    }

    /**
     * 由同步驱动器定时调用：按重建 → 补齐 → 校验推进一步。
     * 不开外层事务：补齐与校验各有自己的事务，外层事务会因其内部异常被标记为只回滚而丢失失败原因。
     * 已记录失败原因（lastError 非空）的灰度停在原地，等待用户点击「开始同步」重试，避免反复失败重跑。
     */
    public GrayRelease advance(long id) {
        GrayRelease release = find(id);
        if (release.status() != GrayReleaseStatus.SYNCING || release.lastError() != null) {
            return release;
        }
        int number = release.indexVersionNumber();
        SearchIndexRebuildRun run = runs.findFirstByVersionNumberOrderByIdDesc(number).orElse(null);
        if (run == null || run.state().active()) {
            return release;
        }
        if (run.state() != RebuildRunState.COMPLETED) {
            // 返回 false 说明灰度已被并发改变状态（如已结束），视为无操作。
            store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCING,
                    "同步失败：" + safe(run.getErrorSummary()) + "。可点击「开始同步」重试");
            return find(id);
        }
        try {
            advanceCompletedRun(id, number, run);
        } catch (RuntimeException failure) {
            store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCING,
                    "同步失败：" + safe(failure.getMessage()) + "。可点击「开始同步」重试");
        }
        return find(id);
    }

    private void advanceCompletedRun(long id, int number, SearchIndexRebuildRun run) {
        switch (run.switchState()) {
            case NONE -> preparations.prepare(number);
            case READY -> {
                if (validations.currentReadyReport(number).isPresent()) {
                    store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCED, null);
                } else {
                    SearchIndexValidationReport report = validations.validate(number);
                    if ("PASS".equals(report.getStatus())) {
                        store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCED, null);
                    } else {
                        store.transition(id, GrayReleaseStatus.SYNCING, GrayReleaseStatus.SYNCING,
                                "校验未通过：" + safe(report.getSummary()) + "。如多次重试仍失败，请结束该灰度后重新创建");
                    }
                }
            }
            default -> { /* PREPARING：补齐进行中，等待下一轮 */ }
        }
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
                .filter(run -> run.state().active() || run.switchState() == IndexSwitchState.PREPARING)
                .ifPresent(run -> {
                    throw new ConflictException("同步正在进行，请等待当前重建或补齐结束后再结束灰度");
                });
        // 无论写入当前是否开启都做管理员停用，防止之后的全局切换准备重新开启灰度版本写入。
        versions.findByVersionNumber(number)
                .filter(version -> !version.isAdminDisabled())
                .ifPresent(version -> {
                    try {
                        enablement.disable(version.getVersionNumber());
                    } catch (IllegalStateException justStarted) {
                        // 读取最新 run 之后恰好有重建或补齐开始，disable 的空闲检查会拒绝
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
