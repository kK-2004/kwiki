package com.kwiki.infrastructure.ai.registry;

import com.kwiki.usage.ModelUsageContext;
import com.kwiki.usage.ModelUsageRecorder;
import com.kwiki.usage.UsageEvent;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 对话模型用量采集：请求时在调用线程上判定来源/用户/requestId 并记下开始时间，
 * 响应或失败时（可能在 HTTP 回调线程）提交用量事件。任何异常都被吞掉，不影响调用。
 */
public final class ModelUsageListener implements ChatModelListener {

    private static final String STARTED = ModelUsageListener.class.getName() + ".started";

    private record Started(ModelUsageContext.Resolved owner, long startNanos) {
    }

    private final ModelUsageRecorder recorder;
    private final String step;
    private final String providerName;
    private final String modelName;

    public ModelUsageListener(ModelUsageRecorder recorder, String step, String providerName, String modelName) {
        this.recorder = recorder;
        this.step = step;
        this.providerName = providerName;
        this.modelName = modelName;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        try {
            context.attributes().put(STARTED, new Started(ModelUsageContext.resolve(), System.nanoTime()));
        } catch (RuntimeException ignored) {
            // 采集失败不影响模型调用
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        try {
            TokenUsage usage = context.chatResponse() == null ? null : context.chatResponse().tokenUsage();
            record(started(context.attributes().get(STARTED)), usage, true, null);
        } catch (RuntimeException ignored) {
            // 采集失败不影响模型调用
        }
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        try {
            String errorType = context.error() == null ? null : context.error().getClass().getSimpleName();
            record(started(context.attributes().get(STARTED)), null, false, errorType);
        } catch (RuntimeException ignored) {
            // 采集失败不影响模型调用
        }
    }

    private static Started started(Object value) {
        return value instanceof Started started ? started : new Started(ModelUsageContext.resolve(), -1);
    }

    private void record(Started started, TokenUsage usage, boolean success, String errorType) {
        long latency = started.startNanos() < 0 ? 0
                : TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started.startNanos());
        ModelUsageContext.Resolved owner = started.owner();
        recorder.record(new UsageEvent(Instant.now(), owner.source(), step, providerName, modelName,
                owner.userId(), owner.requestId(),
                usage == null || usage.inputTokenCount() == null ? 0 : usage.inputTokenCount(),
                usage == null || usage.outputTokenCount() == null ? 0 : usage.outputTokenCount(),
                latency, success, errorType));
    }
}
