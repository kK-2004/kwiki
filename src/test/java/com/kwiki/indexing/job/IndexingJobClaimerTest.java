package com.kwiki.indexing.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcOperations;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class IndexingJobClaimerTest {

    @Mock
    JdbcOperations jdbc;

    private IndexingJobClaimer claimer(boolean withJdbc) {
        ObjectProvider<JdbcOperations> provider = new ObjectProvider<>() {
            @Override
            public JdbcOperations getIfAvailable() {
                return withJdbc ? jdbc : null;
            }
        };
        return new IndexingJobClaimer(provider, 300);
    }

    @Test
    void claimExecutesGuardedLeaseUpdateThenSelectsOwnedIds() {
        lenient().when(jdbc.queryForList(anyString(), eq(Long.class),
                anyString(), any(Instant.class))).thenReturn(List.of(1L, 2L));

        List<Long> claimed = claimer(true).claim("worker-1", 20);

        assertThat(claimed).containsExactly(1L, 2L);
        verify(jdbc).update(anyString(), anyString(), any(Instant.class),
                any(Instant.class), any(Instant.class), eq(20));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), anyString(), any(Instant.class),
                any(Instant.class), any(Instant.class), eq(20));
        String update = sql.getValue();
        assertThat(update)
                .as("must lease only eligible states with elapsed backoff")
                .contains("state IN ('PENDING', 'RETRY_WAIT')", "next_attempt_at IS NULL",
                        "next_attempt_at <= ?")
                .as("expired leases must be reclaimable")
                .contains("state = 'LEASED'", "lease_expires_at <= ?")
                .as("batch must be bounded")
                .contains("LIMIT ?")
                .as("claiming increments attempts and stamps owner/expiry")
                .contains("attempts = attempts + 1", "lease_owner = ?", "lease_expires_at = ?");
    }

    @Test
    void selectionIsScopedToOwnerAndExpiryStamp() {
        lenient().when(jdbc.queryForList(anyString(), eq(Long.class),
                anyString(), any(Instant.class))).thenReturn(List.of());

        claimer(true).claim("worker-2", 5);

        ArgumentCaptor<String> select = ArgumentCaptor.forClass(String.class);
        verify(jdbc).queryForList(select.capture(), eq(Long.class), eq("worker-2"),
                any(Instant.class));
        assertThat(select.getValue()).contains("lease_owner = ?", "lease_expires_at = ?");
    }

    @Test
    void missingJdbcTemplateClaimsNothing() {
        assertThat(claimer(false).claim("worker-1", 10)).isEmpty();
        verifyNoInteractions(jdbc);
    }
}
