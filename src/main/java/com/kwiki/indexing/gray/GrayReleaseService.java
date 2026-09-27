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
