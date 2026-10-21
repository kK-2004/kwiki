package com.kwiki.eval;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 未启用 Redis 时的进程内存储：语义与 Redis 实现一致，应用重启即丢失。 */
public class InMemoryEvalRunStore implements EvalRunStore {

    private final Map<String, RunRecord> runs = new ConcurrentHashMap<>();
    private final Map<String, Map<String, CaseResult>> cases = new ConcurrentHashMap<>();

    @Override
    public void create(RunMeta meta) {
        runs.put(meta.runId(), new RunRecord(meta, EvalRunState.RUNNING, null, null, false, meta.createdAt(), false));
        cases.put(meta.runId(), new ConcurrentHashMap<>());
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
    public void saveCase(String runId, CaseResult result) {
        Map<String, CaseResult> stored = cases.get(runId);
        if (stored != null) stored.put(result.evalCase().id(), result);
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
    public void markRunning(String runId, Instant now) {
        runs.computeIfPresent(runId, (id, run) ->
                new RunRecord(run.meta(), EvalRunState.RUNNING, null, null, false, now, false));
    }

    @Override
    public void finish(String runId, EvalRunState state, EvalSummary summary, boolean reportPartial, String error) {
        runs.computeIfPresent(runId, (id, run) ->
                new RunRecord(run.meta(), state, summary, error, reportPartial, run.heartbeatAt(), false));
    }

    @Override
    public void heartbeat(String runId, Instant now) {
        runs.computeIfPresent(runId, (id, run) -> new RunRecord(run.meta(), run.state(), run.summary(),
                run.error(), run.reportPartial(), now, run.cancelRequested()));
    }

    @Override
    public void requestCancel(String runId) {
        runs.computeIfPresent(runId, (id, run) -> new RunRecord(run.meta(), run.state(), run.summary(),
                run.error(), run.reportPartial(), run.heartbeatAt(), true));
    }

    @Override
    public void delete(String runId) {
        runs.remove(runId);
        cases.remove(runId);
    }
}
