package com.kwiki.rag.tool;

import com.kwiki.rag.activity.AgenticDebugLogger;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.rag.orchestration.RunFailure;
import com.kwiki.rag.retrieval.ConcurrentRecallService;
import com.kwiki.rag.retrieval.HybridRetrievalOrchestrator;
import com.kwiki.rag.retrieval.RetrievalLifecycleService;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * agent 工具的代码侧调度器：LLM 只给出策略，这里负责重新鉴权、callId 幂等、工具预算、
 * 调用执行器、错误映射、结果规范化与 tool-result-v2 校验，以及 DEBUG 日志。
 * 取消 / 超时 / 授权变化会中止整个 run；预期内的工具失败只产生 ERROR 结果。
 */
@Component
public final class AgentToolDispatcher {
    private static final int MAX_REFS = 40;
    private static final int MAX_DEGRADATIONS = 20;
    private static final int MAX_TEXT = 1000;
    private static final int MAX_CODE = 100;

    private final ToolRegistry registry;
    private final Map<String, AgentToolExecutor> executors = new LinkedHashMap<>();
    private final AgenticDebugLogger debug;

    public AgentToolDispatcher(ToolRegistry registry, List<AgentToolExecutor> executors,
                               AgenticDebugLogger debug) {
        this.registry = registry;
        this.debug = debug;
        for (AgentToolExecutor executor : executors) {
            registry.get(executor.name());
            if (this.executors.putIfAbsent(executor.name(), executor) != null) {
                throw new IllegalStateException("duplicate tool executor");
            }
        }
    }

    public ToolResult execute(ToolRegistry.ValidatedAgentCall call, ToolExecutionContext context,
                              Map<String, ToolResult> completed, Map<String, String> identities) {
        RunContext run = context.run();
        run.authorize();
        ToolCall raw = call.call();
        String identity = raw.name() + ":" + call.arguments().signature();
        String prior = identities.get(raw.callId());
        if (prior != null) {
            if (!prior.equals(identity)) throw new RunFailure("conflicting-call-id");
            return completed.get(raw.callId());
        }
        AgentToolExecutor executor = executors.get(raw.name());
        if (executor == null) throw new RunFailure("tool-unavailable");
        run.toolCall();
        identities.put(raw.callId(), identity);
        long started = System.nanoTime();
        debug.stage(run, "tool-" + raw.callId(), context.round(), null, "tool", "start", 0, () -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("tool", raw.name());
            fields.put("callId", raw.callId());
            fields.put("arguments", AgenticDebugLogger.boundedQuery(identity));
            fields.put("pool", context.pool().summary());
            return fields;
        });
        ToolResult result;
        try {
            result = success(raw.callId(), executor.execute(call.arguments(), context));
            run.authorize();
        } catch (AgentToolExecutor.ToolFailure failure) {
            run.authorize();
            result = error(raw.callId(), failure.code());
        } catch (HybridRetrievalOrchestrator.StaleScopeException stale) {
            throw new RunFailure("authorization-changed");
        } catch (RunFailure failure) {
            run.authorize(); // 取消 / 超时 / 授权变化照常中止整个 run
            result = error(raw.callId(), failure.getMessage());
        } catch (RuntimeException failure) {
            run.authorize();
            result = error(raw.callId(), retrievalFailure(failure) ? "retrieval-failed" : "tool-failed");
        }
        registry.validate("tool-result-v2", ToolRegistry.json(result));
        completed.put(raw.callId(), result);
        ToolResult finished = result;
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        debug.stage(run, "tool-" + raw.callId(), context.round(), null, "tool", "complete", elapsedMs, () -> {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("tool", raw.name());
            fields.put("callId", raw.callId());
            fields.put("result", finished.status());
            fields.put("errorCode", finished.errorCode());
            fields.put("evidenceRefs", finished.evidenceRefs().size());
            fields.put("degradations", finished.degradations());
            fields.put("stats", finished.stats());
            fields.put("pool", context.pool().summary());
            return fields;
        });
        return result;
    }

    private static boolean retrievalFailure(RuntimeException failure) {
        return failure instanceof ConcurrentRecallService.RetrievalBranchException
                || failure instanceof RetrievalLifecycleService.LifecycleFilterException;
    }

    private static ToolResult success(String callId, AgentToolExecutor.Outcome outcome) {
        return new ToolResult(callId, "SUCCESS", bounded(outcome.evidenceRefs(), MAX_REFS),
                bounded(outcome.degradations(), MAX_DEGRADATIONS), null, outcome.stats());
    }

    private static ToolResult error(String callId, String code) {
        String bounded = code == null || code.isBlank() ? "tool-failed"
                : code.length() <= MAX_CODE ? code : code.substring(0, MAX_CODE);
        return new ToolResult(callId, "ERROR", List.of(), List.of(), bounded, Map.of());
    }

    private static List<String> bounded(List<String> values, int max) {
        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT))
                .distinct()
                .limit(max)
                .toList();
    }
}
