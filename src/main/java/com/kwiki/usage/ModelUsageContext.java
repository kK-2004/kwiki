package com.kwiki.usage;

import com.kwiki.rag.orchestration.RunContext;

/**
 * 用量归属判定：显式作用域（评测、索引任务）优先；其次是当前线程绑定的请求上下文；
 * 都没有时归为后台任务（用户 0）。作用域可嵌套，关闭时恢复外层作用域。
 */
public final class ModelUsageContext {

    /** 判定结果；step 为 null 表示由调用方使用默认步骤。 */
    public record Resolved(UsageSource source, long userId, String requestId, String step) {
    }

    /** 作用域句柄，配合 try-with-resources 使用。 */
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private record Scope(UsageSource source, Long userId, String step) {
    }

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();

    private ModelUsageContext() {
    }

    public static Handle open(UsageSource source, Long userId) {
        return open(source, userId, null);
    }

    public static Handle open(UsageSource source, Long userId, String step) {
        Scope previous = CURRENT.get();
        CURRENT.set(new Scope(source, userId, step));
        return () -> {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        };
    }

    public static Resolved resolve() {
        Scope scope = CURRENT.get();
        RunContext run = RunContext.current();
        String requestId = run == null ? null : run.requestId;
        long runUser = run == null ? 0L : run.scope.userId();
        if (scope != null) {
            return new Resolved(scope.source(), scope.userId() != null ? scope.userId() : runUser,
                    requestId, scope.step());
        }
        if (run != null) return new Resolved(run.usageSource(), runUser, requestId, null);
        return new Resolved(UsageSource.BACKGROUND, 0L, null, null);
    }
}
