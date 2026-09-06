package com.kwiki.indexing.job;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Transition matrix for the indexing-job state machine, written before worker behavior. */
class IndexingJobStateMachineTest {

    private static IndexingJob job(IndexingJobState state) {
        IndexingJob job = new IndexingJob("UPSERT", "PAGE", 1, 1L, "PAGE:1:1:UPSERT");
        // force initial state for matrix testing
        org.springframework.test.util.ReflectionTestUtils.setField(job, "state", state);
        return job;
    }

    @Test
    void pendingCanOnlyBeLeased() {
        assertThat(IndexingJobState.PENDING.canTransitionTo(IndexingJobState.LEASED)).isTrue();
        assertThat(IndexingJobState.PENDING.canTransitionTo(IndexingJobState.COMPLETED)).isFalse();
        assertThat(IndexingJobState.PENDING.canTransitionTo(IndexingJobState.FAILED)).isFalse();
    }

    @Test
    void leasedCanCompleteRetryFailOrReturnToPending() {
        assertThat(IndexingJobState.LEASED.canTransitionTo(IndexingJobState.COMPLETED)).isTrue();
        assertThat(IndexingJobState.LEASED.canTransitionTo(IndexingJobState.RETRY_WAIT)).isTrue();
        assertThat(IndexingJobState.LEASED.canTransitionTo(IndexingJobState.FAILED)).isTrue();
        assertThat(IndexingJobState.LEASED.canTransitionTo(IndexingJobState.PENDING)).isTrue();
    }

    @Test
    void retryWaitLeadsBackToLeaseOrPending() {
        assertThat(IndexingJobState.RETRY_WAIT.canTransitionTo(IndexingJobState.LEASED)).isTrue();
        assertThat(IndexingJobState.RETRY_WAIT.canTransitionTo(IndexingJobState.COMPLETED)).isFalse();
    }

    @Test
    void completedIsTerminal() {
        for (IndexingJobState next : IndexingJobState.values()) {
            assertThat(IndexingJobState.COMPLETED.canTransitionTo(next))
                    .as("COMPLETED -> %s", next).isFalse();
        }
    }

    @Test
    void failedOnlyReopensThroughAdminRetry() {
        assertThat(IndexingJobState.FAILED.canTransitionTo(IndexingJobState.PENDING)).isTrue();
        assertThat(IndexingJobState.FAILED.canTransitionTo(IndexingJobState.LEASED)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(value = IndexingJobState.class, names = {"PENDING", "RETRY_WAIT",
            "COMPLETED", "FAILED"})
    void illegalTransitionsThrow(IndexingJobState state) {
        IndexingJob job = job(state);
        assertThatThrownBy(() -> job.transitionTo(IndexingJobState.COMPLETED, "w",
                null, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("illegal indexing job transition");
    }

    @Test
    void failureBeforeMaxAttemptsGoesToRetryWaitWithSanitizedFields() {
        IndexingJob job = job(IndexingJobState.LEASED);
        job.incrementAttempts(); // attempts = 1 < 8

        job.recordFailure("ElasticsearchException", "bulk reject: 3 items", 8,
                Instant.now().plusSeconds(60));

        assertThat(job.getState()).isEqualTo(IndexingJobState.RETRY_WAIT);
        assertThat(job.getLastErrorClass()).isEqualTo("ElasticsearchException");
        assertThat(job.getLeaseOwner()).as("lease must be released").isNull();
        assertThat(job.getNextAttemptAt()).isNotNull();
    }

    @Test
    void failureAtMaxAttemptsIsTerminal() {
        IndexingJob job = job(IndexingJobState.LEASED);
        for (int i = 0; i < 7; i++) {
            job.incrementAttempts();
        }
        job.recordFailure("QwenEmbeddingException", "timeout", 8, null);

        assertThat(job.getState()).isEqualTo(IndexingJobState.FAILED);
    }

    @Test
    void successClearsLeaseMetadata() {
        IndexingJob job = job(IndexingJobState.LEASED);
        job.recordSuccess();
        assertThat(job.getState()).isEqualTo(IndexingJobState.COMPLETED);
        assertThat(job.getLeaseOwner()).isNull();
        assertThat(job.getLeaseExpiresAt()).isNull();
    }
}
