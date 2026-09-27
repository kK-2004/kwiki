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
