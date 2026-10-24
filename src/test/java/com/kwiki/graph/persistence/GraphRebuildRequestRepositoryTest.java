package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 待重建记录 SQL 的语义约束：这些约束在 mock 环境下只能通过语句结构验证。 */
class GraphRebuildRequestRepositoryTest {

    @SuppressWarnings("unchecked")
    private static GraphRebuildRequestRepository repository(JdbcOperations jdbc) {
        ObjectProvider<JdbcOperations> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbc);
        return new GraphRebuildRequestRepository(provider,
                Clock.fixed(Instant.parse("2026-10-24T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void backoffResetIsEvaluatedBeforeDirtyEpochsAreOverwritten() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        repository(jdbc).markDirty(7, 5, 2, false);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        String update = sql.getValue().substring(sql.getValue().indexOf("ON DUPLICATE KEY UPDATE"));
        // MySQL 从左到右赋值：IF 必须在 dirty epoch 被覆盖前读取旧值
        int resetAttempts = update.indexOf("attempt_count = IF(");
        int resetBackoff = update.indexOf("next_attempt_at = IF(");
        int overwriteEpoch = update.indexOf("dirty_content_epoch = VALUES(");
        int overwriteSecurity = update.indexOf("dirty_security_epoch = VALUES(");
        assertThat(resetAttempts).isPositive();
        assertThat(resetBackoff).isPositive();
        assertThat(resetAttempts).isLessThan(overwriteEpoch).isLessThan(overwriteSecurity);
        assertThat(resetBackoff).isLessThan(overwriteEpoch).isLessThan(overwriteSecurity);
    }

    @Test
    void releasingASubmissionClearsBatchAndBackoffWithoutTouchingOutcome() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        repository(jdbc).releaseSubmission(7);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq(7L));
        assertThat(sql.getValue())
                .contains("last_submitted_batch_id = NULL")
                .contains("next_attempt_at = NULL")
                .doesNotContain("last_outcome")
                .doesNotContain("attempt_count");
    }

    @Test
    @SuppressWarnings("unchecked")
    void publishedEpochsComeFromTheActiveSnapshotNotThePublicationRow() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        repository(jdbc).publishedEpochs(7, 3);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(),
                any(org.springframework.jdbc.core.ResultSetExtractor.class), eq(7L), eq(3));
        // 回滚后发布行存的是当前 epoch，快照本身是旧的：必须取快照上的 epoch
        assertThat(sql.getValue())
                .contains("JOIN graph_snapshot s ON s.id = p.active_snapshot_id")
                .contains("SELECT s.content_epoch, s.security_epoch");
    }

    @Test
    void onlyActiveKnowledgeBasesQualify() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(7L))).thenReturn(0);
        when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(8L))).thenReturn(1);
        GraphRebuildRequestRepository repository = repository(jdbc);

        assertThat(repository.knowledgeBaseActive(7)).isFalse();
        assertThat(repository.knowledgeBaseActive(8)).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForObject(sql.capture(), eq(Integer.class), eq(8L));
        assertThat(sql.getValue()).contains("status = 'ACTIVE'");
    }
}
