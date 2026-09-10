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
 * One-shot startup sweep that enqueues DELETE jobs for every STORED non-image
 * attachment left over from the old "every attachment is indexed" behavior.
 * The worker intercepts the upsert path with the same image-only rule, so this
 * only clears historical chunks; attachment files, metadata, and page
 * references are preserved. Idempotent: repeated boots re-enqueue the same
 * keys and the jobs become no-ops once the chunks are gone.
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
