package com.kwiki.indexing.version;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SwitchPreparationBarrierServiceTest {

    @Test
    void unresolvedTargetGapKeepsPreparingAndARepeatedPassCanBecomeReady() {
        SearchIndexVersionRepository versions = mock(SearchIndexVersionRepository.class);
        SearchIndexRebuildRunRepository runs = mock(SearchIndexRebuildRunRepository.class);
        SearchIndexRebuildRangeRepository ranges = mock(SearchIndexRebuildRangeRepository.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        SearchIndexRebuildRun run = preparingRun();
        SearchIndexRebuildRange range = new SearchIndexRebuildRange(91, "PAGE", 1, 10);
        range.captureTailUpperBound(15);
        range.recordTailBatch(15, 5);
        SearchIndexVersion version = SearchIndexVersion.bootstrapped(
                2, "kwiki-chunks-v2", config(), "mapping");
        AtomicLong gaps = new AtomicLong(1);

        when(runs.findById(91L)).thenReturn(Optional.of(run));
        when(runs.findByIdForUpdate(91L)).thenReturn(Optional.of(run));
        when(ranges.findByRunIdOrderByResourceType(91L)).thenReturn(List.of(range));
        when(versions.findAllActiveForUpdate()).thenReturn(List.of(version));
        when(versions.findByVersionNumber(2)).thenReturn(Optional.of(version));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("MAX(id)")) return 30L;
                    if (sql.contains("search_index_change_event e")) return gaps.get();
                    if (sql.contains("idempotency_key LIKE")) return 0L;
                    throw new AssertionError(sql);
                });
        SwitchPreparationBarrierService service = new SwitchPreparationBarrierService(
                versions, runs, ranges, jdbc, transactions(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        var blocked = service.advance(91);
        assertThat(blocked.state()).isEqualTo(IndexSwitchState.PREPARING);
        assertThat(blocked.unresolvedOperations()).isEqualTo(1);

        gaps.set(0);
        var ready = service.advance(91);
        assertThat(ready.state()).isEqualTo(IndexSwitchState.READY);
        assertThat(version.getCatchupStatus()).isEqualTo(IndexCatchupStatus.CURRENT.name());
    }

    private static SearchIndexRebuildRun preparingRun() {
        SearchIndexRebuildRun run = SearchIndexRebuildRun.create(2, 1,
                RebuildRunKind.INITIAL, 1, "{}", "admin", "owner",
                Duration.ofMinutes(1), 10, Instant.EPOCH);
        ReflectionTestUtils.setField(run, "id", 91L);
        run.complete(Instant.EPOCH);
        run.startSwitchPreparation(20, Instant.EPOCH);
        run.advanceReplayCursor(20);
        return run;
    }

    private static EditableIndexConfig config() {
        return new EditableIndexConfig("parser", "chunker", "default", "model", 1024, 1);
    }

    private static PlatformTransactionManager transactions() {
        return new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
    }
}
