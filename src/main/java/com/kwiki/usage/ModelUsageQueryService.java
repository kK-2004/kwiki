package com.kwiki.usage;

import com.kk2004.common.exception.BusinessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 用量查询：校验范围、选择并按需升级分桶粒度、按本地时区对齐、补 0。 */
@Service
public class ModelUsageQueryService {

    static final long MAX_BUCKETS = 2_000;
    static final Duration MAX_RANGE = Duration.ofDays(366);
    private static final List<Long> LADDER = List.of(300L, 3_600L, 86_400L);

    public record Series(String step, String stepLabel, String provider, String model, long[] calls,
                         long[] failures, long[] inputTokens, long[] outputTokens, long[] latencyMs) {
    }

    public record SeriesResponse(long bucketSeconds, List<Long> buckets, List<Series> series) {
    }

    public record UserView(long userId, String displayName, long totalTokens) {
    }

    private final ModelUsageRepository repository;

    public ModelUsageQueryService(ModelUsageRepository repository) {
        this.repository = repository;
    }

    public SeriesResponse series(Instant from, Instant to, Set<UsageSource> sources, Set<Long> users,
                                 String bucket, int tzOffsetMinutes) {
        validateRange(from, to);
        long size = bucketSeconds(from, to, bucket);
        requireStore();
        long offset = tzOffsetMinutes * 60L;
        long start = Math.floorDiv(from.getEpochSecond() + offset, size) * size - offset;
        List<Long> buckets = new ArrayList<>();
        for (long t = start; t < to.getEpochSecond(); t += size) buckets.add(t * 1000);
        Map<String, Series> series = new LinkedHashMap<>();
        for (ModelUsageRepository.SeriesRow row : repository.querySeries(from, to, sources, users, size, offset)) {
            int index = (int) ((row.bucketEpochSeconds() - start) / size);
            if (index < 0 || index >= buckets.size()) continue;
            Series target = series.computeIfAbsent(row.step() + "\u0000" + row.provider() + "\u0000" + row.model(),
                    key -> new Series(row.step(), UsageSteps.label(row.step()), row.provider(), row.model(),
                            new long[buckets.size()], new long[buckets.size()], new long[buckets.size()],
                            new long[buckets.size()], new long[buckets.size()]));
            target.calls()[index] += row.calls();
            target.failures()[index] += row.failures();
            target.inputTokens()[index] += row.inputTokens();
            target.outputTokens()[index] += row.outputTokens();
            target.latencyMs()[index] += row.latencyMs();
        }
        return new SeriesResponse(size, buckets, List.copyOf(series.values()));
    }

    public List<UserView> users(Instant from, Instant to, Set<UsageSource> sources) {
        validateRange(from, to);
        requireStore();
        return repository.queryUsers(from, to, sources).stream()
                .map(row -> new UserView(row.userId(), row.userId() == 0 ? "系统"
                        : row.displayName() == null ? "用户 " + row.userId() : row.displayName(), row.totalTokens()))
                .toList();
    }

    /** 参数校验先于存储可用性检查：非法请求总是得到 400。 */
    private static void validateRange(Instant from, Instant to) {
        if (from == null || to == null || !to.isAfter(from)) throw new BusinessException(400, "invalid-range");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) throw new BusinessException(400, "range-too-long");
    }

    private void requireStore() {
        if (!repository.available()) throw new BusinessException(503, "usage-store-unavailable");
    }

    private static long bucketSeconds(Instant from, Instant to, String bucket) {
        long range = Duration.between(from, to).getSeconds();
        long size = switch (bucket == null ? "auto" : bucket) {
            case "auto" -> range <= 86_400 ? 300 : range <= 7 * 86_400 ? 3_600 : 86_400;
            case "5m" -> 300;
            case "1h" -> 3_600;
            case "1d" -> 86_400;
            default -> throw new BusinessException(400, "invalid-bucket");
        };
        for (long candidate : LADDER) {
            if (candidate >= size && Math.ceilDiv(range, candidate) <= MAX_BUCKETS) return candidate;
        }
        return 86_400;
    }
}
