package com.kwiki.usage;

import com.kk2004.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModelUsageQueryServiceTest {
    final ModelUsageRepository repository = mock(ModelUsageRepository.class);
    final ModelUsageQueryService service = new ModelUsageQueryService(repository);

    // 东八区 2026-10-21 当天：UTC 2026-10-20T16:00Z ~ 2026-10-21T16:00Z
    final Instant from = Instant.parse("2026-10-20T16:00:00Z");
    final Instant to = Instant.parse("2026-10-21T16:00:00Z");

    @Test
    void autoBucketForOneDayIsFiveMinutesAlignedAndZeroFilled() {
        when(repository.available()).thenReturn(true);
        long first = from.getEpochSecond();
        when(repository.querySeries(eq(from), eq(to), any(), any(), eq(300L), eq(28_800L))).thenReturn(List.of(
                new ModelUsageRepository.SeriesRow(first, "ANSWER", "dashscope", "qwen-plus", 2, 0, 100, 20, 50),
                new ModelUsageRepository.SeriesRow(first + 600, "ANSWER", "dashscope", "qwen-plus", 1, 1, 10, 0, 5),
                new ModelUsageRepository.SeriesRow(first, "QUERY_EMBEDDING", "dashscope", "v4", 3, 0, 30, 0, 9)));

        var response = service.series(from, to, Set.of(), Set.of(), "auto", 480);

        assertThat(response.bucketSeconds()).isEqualTo(300);
        assertThat(response.buckets()).hasSize(288);
        assertThat(response.buckets().getFirst()).isEqualTo(from.toEpochMilli());
        var answer = response.series().stream().filter(s -> s.step().equals("ANSWER")).findFirst().orElseThrow();
        assertThat(answer.stepLabel()).isEqualTo("回答生成");
        assertThat(answer.inputTokens()[0]).isEqualTo(100);
        assertThat(answer.inputTokens()[1]).isZero();
        assertThat(answer.inputTokens()[2]).isEqualTo(10);
        assertThat(answer.failures()[2]).isEqualTo(1);
        assertThat(response.series()).extracting(ModelUsageQueryService.Series::stepLabel).contains("查询向量");
    }

    @Test
    void longRangesEscalateBucketSize() {
        when(repository.available()).thenReturn(true);
        when(repository.querySeries(any(), any(), any(), any(), anyLong(), anyLong())).thenReturn(List.of());

        assertThat(service.series(from, from.plusSeconds(3 * 86_400), Set.of(), Set.of(), "auto", 480)
                .bucketSeconds()).isEqualTo(3600);
        assertThat(service.series(from, from.plusSeconds(30 * 86_400), Set.of(), Set.of(), "auto", 480)
                .bucketSeconds()).isEqualTo(86_400);
        // 手动 5m 查 30 天会超过 2000 个桶，自动升到 1h
        assertThat(service.series(from, from.plusSeconds(30 * 86_400), Set.of(), Set.of(), "5m", 480)
                .bucketSeconds()).isEqualTo(3600);
    }

    @Test
    void dailyBucketsAlignToLocalMidnight() {
        when(repository.available()).thenReturn(true);
        when(repository.querySeries(any(), any(), any(), any(), anyLong(), anyLong())).thenReturn(List.of());

        var response = service.series(Instant.parse("2026-10-01T03:00:00Z"), Instant.parse("2026-10-21T03:00:00Z"),
                Set.of(), Set.of(), "1d", 480);

        // 东八区零点 = UTC 前一日 16:00
        assertThat(Instant.ofEpochMilli(response.buckets().getFirst()))
                .isEqualTo(Instant.parse("2026-09-30T16:00:00Z"));
    }

    @Test
    void sourcesAndUsersArePassedThrough() {
        when(repository.available()).thenReturn(true);
        when(repository.querySeries(any(), any(), any(), any(), anyLong(), anyLong())).thenReturn(List.of());

        service.series(from, to, Set.of(UsageSource.EVAL), Set.of(0L, 7L), "1h", 480);

        verify(repository).querySeries(from, to, Set.of(UsageSource.EVAL), Set.of(0L, 7L), 3600L, 28_800L);
    }

    @Test
    void invalidRangesAreRejected() {
        assertThatThrownBy(() -> service.series(to, from, Set.of(), Set.of(), "auto", 480))
                .isInstanceOf(BusinessException.class).hasMessageContaining("invalid-range");
        assertThatThrownBy(() -> service.series(from, from.plusSeconds(367L * 86_400), Set.of(), Set.of(), "auto", 480))
                .isInstanceOf(BusinessException.class).hasMessageContaining("range-too-long");
        assertThatThrownBy(() -> service.series(from, to, Set.of(), Set.of(), "2m", 480))
                .isInstanceOf(BusinessException.class).hasMessageContaining("invalid-bucket");
    }

    @Test
    void usersShowSystemForZero() {
        when(repository.available()).thenReturn(true);
        when(repository.queryUsers(from, to, Set.of())).thenReturn(List.of(
                new ModelUsageRepository.UserRow(7, "张三", 900), new ModelUsageRepository.UserRow(0, null, 100)));

        assertThat(service.users(from, to, Set.of())).containsExactly(
                new ModelUsageQueryService.UserView(7, "张三", 900),
                new ModelUsageQueryService.UserView(0, "系统", 100));
    }

    @Test
    void missingDatabaseIsServiceUnavailable() {
        when(repository.available()).thenReturn(false);
        assertThatThrownBy(() -> service.series(from, to, Set.of(), Set.of(), "auto", 480))
                .isInstanceOf(BusinessException.class).hasMessageContaining("usage-store-unavailable");
    }
}
