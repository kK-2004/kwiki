package com.kwiki.eval;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 未启用 Redis 时的进程内存储：语义与 Redis 实现一致（排队索引、写入令牌、已上报标记），应用重启即丢失。 */
public class InMemoryEvalRunStore implements EvalRunStore {

    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CaseResult>> cases = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> reported = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> tokens = new ConcurrentHashMap<>();
    /** 排队索引：runId → queuedAt 毫秒；位次按 (queuedAt, runId) 比较，与 Redis ZSET 语义一致。 */
    private final Map<String, Long> queued = new ConcurrentHashMap<>();

    @Override
    public void create(RunMeta meta) {
        runs.put(meta.runId(), new RunRecord(meta, EvalRunState.QUEUED, null, null, false,
                meta.createdAt(), false, null, meta.createdAt()));
        cases.put(meta.runId(), new ConcurrentHashMap<>());
        reported.put(meta.runId(), ConcurrentHashMap.newKeySet());
        tokens.put(meta.runId(), new AtomicLong());
        queued.put(meta.runId(), meta.createdAt().toEpochMilli());
    }

    @Override
    public Optional<RunRecord> find(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public List<RunRecord> list(int limit) {
        return runs.values().stream()
                .sorted(Comparator.comparing((RunRecord run) -> run.meta().createdAt()).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public boolean markQueued(String runId, String submittedBy, Instant now, long staleAfterMillis) {
        RunRecord run = runs.get(runId);
        if (run == null) return false;
        EvalRunState state = run.state();
        boolean resumable = state == EvalRunState.CANCELLED || state == EvalRunState.FAILED
                || (state == EvalRunState.RUNNING && stale(run, now, staleAfterMillis));
        if (!resumable) return false;
        requeue(run, submittedBy, now);
        return true;
    }

    @Override
    public synchronized boolean markRetryQueued(String runId, String submittedBy, Instant now,
                                                long staleAfterMillis, Set<String> caseIds) {
        RunRecord run = runs.get(runId);
        if (run == null) return false;
        EvalRunState state = run.state();
        boolean retryable = state == EvalRunState.COMPLETED || state == EvalRunState.REPORT_PARTIAL
                || state == EvalRunState.CANCELLED || state == EvalRunState.FAILED
                || (state == EvalRunState.RUNNING && stale(run, now, staleAfterMillis));
        if (!retryable) return false;
        caseIds.forEach(caseId -> {
            cases.getOrDefault(runId, new ConcurrentHashMap<>()).remove(caseId);
            reported.getOrDefault(runId, ConcurrentHashMap.newKeySet()).remove(caseId);
        });
        requeue(run, submittedBy, now);
        return true;
    }

    private void requeue(RunRecord run, String submittedBy, Instant now) {
        RunMeta meta = run.meta();
        runs.computeIfPresent(meta.runId(), (id, current) -> new RunRecord(
                new RunMeta(meta.runId(), meta.runName(), meta.dataset(), meta.kbId(), meta.notes(),
                        meta.localOnly(), meta.total(), meta.config(), meta.createdAt(), submittedBy),
                EvalRunState.QUEUED, null, null, false, current.heartbeatAt(), false, null, now));
        queued.put(meta.runId(), now.toEpochMilli());
    }

    private static boolean stale(RunRecord run, Instant now, long staleAfterMillis) {
        Instant beat = run.heartbeatAt() == null ? run.meta().createdAt() : run.heartbeatAt();
        return beat.plusMillis(staleAfterMillis).isBefore(now);
    }

    @Override
    public synchronized boolean cancelQueued(String runId) {
        RunRecord run = runs.get(runId);
        if (run == null || run.state() != EvalRunState.QUEUED) return false;
        runs.computeIfPresent(runId, (id, current) ->
                new RunRecord(current.meta(), EvalRunState.CANCELLED, current.summary(), CANCELLED_ERROR,
                        current.reportPartial(), current.heartbeatAt(), false, current.ownerInstanceId(),
                        current.queuedAt()));
        queued.remove(runId);
        return true;
    }

    @Override
    public long queuePosition(String runId) {
        Long mine = queued.get(runId);
        if (mine == null) return -1;
        long ahead = 0;
        for (Map.Entry<String, Long> entry : queued.entrySet()) {
            if (entry.getKey().equals(runId)) continue;
            long other = entry.getValue();
            if (other < mine || (other == mine && entry.getKey().compareTo(runId) < 0)) ahead++;
        }
        return ahead;
    }

    @Override
    public synchronized long claimOwnership(String runId, String instanceId, Instant now, long staleAfterMillis) {
        RunRecord existing = runs.get(runId);
        if (existing == null || existing.cancelRequested()) return -1;
        boolean waiting = existing.state() == EvalRunState.QUEUED;
        Instant beat = existing.heartbeatAt() == null ? existing.meta().createdAt() : existing.heartbeatAt();
        boolean staleRunning = existing.state() == EvalRunState.RUNNING
                && beat.plusMillis(staleAfterMillis).isBefore(now);
        if (!waiting && !staleRunning) return -1;
        AtomicLong token = tokens.computeIfAbsent(runId, id -> new AtomicLong());
        long value = token.incrementAndGet();
        runs.computeIfPresent(runId, (id, current) -> new RunRecord(current.meta(), EvalRunState.RUNNING,
                null, null, false, now, false, instanceId, current.queuedAt()));
        queued.remove(runId);
        return value;
    }

    @Override
    public boolean saveCase(String runId, long ownerToken, CaseResult result) {
        if (!holds(runId, ownerToken)) return false;
        cases.get(runId).put(result.evalCase().id(), result);
        return true;
    }

    @Override
    public Map<String, CaseResult> cases(String runId) {
        return new LinkedHashMap<>(cases.getOrDefault(runId, Map.of()));
    }

    @Override
    public int caseCount(String runId) {
        return cases.getOrDefault(runId, Map.of()).size();
    }

    @Override
    public Set<String> reportedCaseIds(String runId) {
        return Set.copyOf(reported.getOrDefault(runId, Set.of()));
    }

    @Override
    public boolean markReported(String runId, long ownerToken, String caseId) {
        if (!holds(runId, ownerToken)) return false;
        reported.getOrDefault(runId, ConcurrentHashMap.newKeySet()).add(caseId);
        return true;
    }

    @Override
    public boolean finish(String runId, long ownerToken, EvalRunState state, EvalSummary summary,
                          boolean reportPartial, String error) {
        if (!holds(runId, ownerToken)) return false;
        runs.computeIfPresent(runId, (id, run) -> new RunRecord(run.meta(), state, summary, error, reportPartial,
                run.heartbeatAt(), false, run.ownerInstanceId(), run.queuedAt()));
        return true;
    }

    @Override
    public boolean heartbeat(String runId, long ownerToken, Instant now) {
        if (!holds(runId, ownerToken)) return false;
        runs.computeIfPresent(runId, (id, run) -> new RunRecord(run.meta(), run.state(), run.summary(),
                run.error(), run.reportPartial(), now, run.cancelRequested(), run.ownerInstanceId(),
                run.queuedAt()));
        return true;
    }

    @Override
    public void markFailed(String runId, String error) {
        runs.computeIfPresent(runId, (id, run) -> new RunRecord(run.meta(), EvalRunState.FAILED, null, error,
                run.reportPartial(), run.heartbeatAt(), run.cancelRequested(), run.ownerInstanceId(),
                run.queuedAt()));
        queued.remove(runId);
    }

    @Override
    public void markCancelled(String runId) {
        runs.computeIfPresent(runId, (id, run) -> run.state() == EvalRunState.RUNNING
                ? new RunRecord(run.meta(), EvalRunState.CANCELLED, null, CANCELLED_ERROR, run.reportPartial(),
                run.heartbeatAt(), false, run.ownerInstanceId(), run.queuedAt())
                : run);
        queued.remove(runId);
    }

    @Override
    public synchronized void requestCancel(String runId) {
        runs.computeIfPresent(runId, (id, run) -> new RunRecord(run.meta(), run.state(), run.summary(),
                run.error(), run.reportPartial(), run.heartbeatAt(), true, run.ownerInstanceId(),
                run.queuedAt()));
    }

    @Override
    public void delete(String runId) {
        runs.remove(runId);
        cases.remove(runId);
        reported.remove(runId);
        tokens.remove(runId);
        queued.remove(runId);
    }

    private boolean holds(String runId, long ownerToken) {
        AtomicLong token = tokens.get(runId);
        return token != null && token.get() == ownerToken;
    }

    private static final String CANCELLED_ERROR = "cancelled";
}
