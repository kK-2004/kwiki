package com.kwiki.wiki.archive;

import com.kwiki.indexing.job.IndexingJobEnqueuer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 一次性启动扫描：为旧版"每个附件都被索引"行为遗留的、所有 STORED 状态的非图片
 * 附件入队 DELETE 任务。worker 以相同的"仅图片"规则拦截 upsert 路径，因此本清理
 * 只清除历史分块；附件文件、元数据与页面引用均被保留。幂等：重复启动会重新入队
 * 相同的 key，一旦分块被清除，这些任务即变为空操作。
 */
@Component
public class NonImageAttachmentIndexCleaner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NonImageAttachmentIndexCleaner.class);

    private final JdbcOperations jdbc;
    private final IndexingJobEnqueuer indexingJobs;
    private final boolean enabled;

    public NonImageAttachmentIndexCleaner(ObjectProvider<JdbcOperations> jdbc,
                                          IndexingJobEnqueuer indexingJobs,
                                          @Value("${kwiki.indexing.legacy-media-cleanup-enabled:true}")
                                          boolean enabled) {
        this.jdbc = jdbc == null ? null : jdbc.getIfAvailable();
        this.indexingJobs = indexingJobs;
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || jdbc == null) {
            return;
        }
        try {
            List<Long> attachmentIds = jdbc.query(
                    "SELECT id FROM attachment WHERE status = 'STORED' "
                            + "AND (content_type IS NULL OR content_type NOT LIKE 'image/%') "
                            + "LIMIT 2000",
                    (rs, row) -> rs.getLong(1));
            for (Long attachmentId : attachmentIds) {
                indexingJobs.enqueueAttachmentDelete(attachmentId);
            }
            if (!attachmentIds.isEmpty()) {
                log.info("legacy non-image attachment chunk cleanup enqueued {} attachments",
                        attachmentIds.size());
            }
        } catch (Exception e) {
            log.warn("legacy non-image attachment chunk cleanup failed to start: {}",
                    e.getMessage());
        }
    }
}
