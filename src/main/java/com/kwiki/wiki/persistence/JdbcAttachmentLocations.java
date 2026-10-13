package com.kwiki.wiki.persistence;

import com.kwiki.wiki.attach.AttachmentLocations;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * 附件存储位置与内容丢失登记（V41）。位置优先取附件行，其次取去重记录；
 * 丢失登记在独立事务中提交，不随调用方事务回滚，保证去重不再复用已丢失的文件。
 */
@Repository
public class JdbcAttachmentLocations implements AttachmentLocations {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate independent;

    public JdbcAttachmentLocations(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Optional<Location> locate(long contentCenterFileId) {
        return jdbc.query("""
                SELECT storage_key, storage_source FROM attachment
                WHERE content_center_file_id = ? AND storage_key IS NOT NULL AND storage_source IS NOT NULL
                UNION ALL
                SELECT storage_key, storage_source FROM attachment_blob
                WHERE content_center_file_id = ? AND storage_key IS NOT NULL AND storage_source IS NOT NULL
                LIMIT 1
                """, (rs, n) -> new Location(rs.getString(1), rs.getString(2)),
                contentCenterFileId, contentCenterFileId).stream().findFirst();
    }

    @Override
    public void markMissing(long contentCenterFileId) {
        independent.executeWithoutResult(status -> {
            jdbc.update("""
                    UPDATE attachment SET content_missing_at = COALESCE(content_missing_at, CURRENT_TIMESTAMP(6))
                    WHERE content_center_file_id = ?
                    """, contentCenterFileId);
            jdbc.update("UPDATE attachment_blob SET status = 'MISSING' WHERE content_center_file_id = ? AND status = 'STORED'",
                    contentCenterFileId);
        });
    }
}
