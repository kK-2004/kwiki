package com.kwiki.indexing.version;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 管理员显式控制版本是否继续承接未来索引事件。 */
@Service
public class IndexVersionEnablementService {

    private static final List<String> ACTIVE_RUN_STATES = List.of(
            RebuildRunState.RUNNING.name(), RebuildRunState.PAUSED.name());

    private final SearchIndexVersionRepository versions;
    private final SearchIndexRebuildRunRepository runs;
    private final SwitchPreparationService switchPreparation;

    public IndexVersionEnablementService(SearchIndexVersionRepository versions,
                                         SearchIndexRebuildRunRepository runs,
                                         SwitchPreparationService switchPreparation) {
        this.versions = versions;
        this.runs = runs;
        this.switchPreparation = switchPreparation;
    }

    @Transactional
    public SearchIndexVersion disable(int versionNumber) {
        SearchIndexVersion version = requireActiveVersion(versionNumber);
        requireIdle(versionNumber);
        version.disableByAdministrator();
        return version;
    }

    /**
     * 该事务先恢复未来事件写入，再建立补齐水位。任何准备失败都会回滚
     * writeEnabled/adminDisabled，避免出现“已启用但没有补齐计划”的状态。
     */
    @Transactional
    public SwitchPreparationService.Preparation reenable(int versionNumber) {
        SearchIndexVersion version = requireActiveVersion(versionNumber);
        requireIdle(versionNumber);
        version.reenableByAdministrator();
        versions.flush();
        return switchPreparation.prepare(versionNumber);
    }

    private SearchIndexVersion requireActiveVersion(int versionNumber) {
        SearchIndexVersion version = versions.findByVersionNumberForUpdate(versionNumber)
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown index version: " + versionNumber));
        if (version.getDeletedAt() != null) {
            throw new IllegalStateException("deleted version cannot change enablement");
        }
        return version;
    }

    private void requireIdle(int versionNumber) {
        if (runs.existsByVersionNumberAndStateIn(versionNumber, ACTIVE_RUN_STATES)
                || runs.existsByVersionNumberAndSwitchState(
                versionNumber, IndexSwitchState.PREPARING.name())) {
            throw new IllegalStateException(
                    "version " + versionNumber + " has an active rebuild or catch-up");
        }
    }
}
