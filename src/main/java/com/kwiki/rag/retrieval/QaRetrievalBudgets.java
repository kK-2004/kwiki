package com.kwiki.rag.retrieval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * QA 门控知识路径经校验的预算，按阶段拆分。
 * 扩展阶段会同时放宽两个分支与融合后的 TopK；父级预算
 * 保持固定，因此扩展后的 TopK 不会无界地
 * 扩大父级上下文。finalTopK 绝不能超过 branchTopK——
 * 融合结果大于其自身分支候选集属于配置错误。
 *
 * <p>支持热更新：Nacos 推送配置后（{@link EnvironmentChangeEvent}）重新读取并校验，
 * 通过则原子替换整份快照，已开始的检索阶段沿用取到的旧值；校验不通过则保留原值并记 WARN。
 * 同一次操作需要多个值时取一次 {@link #snapshot()}，避免前后读到不同版本。
 */
@Component
public class QaRetrievalBudgets implements EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(QaRetrievalBudgets.class);

    private static final String PREFIX = "kwiki.retrieval.qa.";
    private static final int DEFAULT_BASE_BRANCH_TOPK = 20;
    private static final int DEFAULT_BASE_FINAL_TOPK = 8;
    private static final int DEFAULT_EXPANDED_BRANCH_TOPK = 50;
    private static final int DEFAULT_EXPANDED_FINAL_TOPK = 20;
    private static final int DEFAULT_BASE_PARENT_LIMIT = 8;
    private static final int DEFAULT_EXPANDED_PARENT_LIMIT = 16;
    private static final long DEFAULT_BASE_CHILD_CHARS = 12000;
    private static final long DEFAULT_EXPANDED_CHILD_CHARS = 24000;
    private static final long DEFAULT_PARENT_CHARS = 24000;

    public record StageBudget(int branchTopK, int finalTopK, int parentLimit,
                              long childCharBudget) {}

    /** 一份经校验的完整预算；热更新时整体替换。 */
    public record Snapshot(int baseBranchTopK, int baseFinalTopK,
                           int expandedBranchTopK, int expandedFinalTopK,
                           int baseParentLimit, int expandedParentLimit,
                           long baseChildCharBudget, long expandedChildCharBudget,
                           long parentCharBudget) {

        public Snapshot {
            if (baseBranchTopK < 1 || baseBranchTopK > 200
                    || expandedBranchTopK < baseBranchTopK || expandedBranchTopK > 200) {
                throw new IllegalArgumentException("qa branch topk out of range");
            }
            if (baseFinalTopK < 1 || baseFinalTopK > baseBranchTopK
                    || expandedFinalTopK < baseFinalTopK || expandedFinalTopK > expandedBranchTopK) {
                throw new IllegalArgumentException("qa final topk must satisfy finalTopK <= branchTopK");
            }
            if (baseParentLimit < 1 || expandedParentLimit < baseParentLimit
                    || expandedParentLimit > 50) {
                throw new IllegalArgumentException("qa parent limit out of range");
            }
            if (baseChildCharBudget < 1000 || expandedChildCharBudget < baseChildCharBudget
                    || parentCharBudget < 1000) {
                throw new IllegalArgumentException("qa context budget out of range");
            }
        }

        public StageBudget base() {
            return new StageBudget(baseBranchTopK, baseFinalTopK, baseParentLimit,
                    baseChildCharBudget);
        }

        public StageBudget expanded() {
            return new StageBudget(expandedBranchTopK, expandedFinalTopK, expandedParentLimit,
                    expandedChildCharBudget);
        }
    }

    private volatile Snapshot snapshot;
    private Environment environment;

    public QaRetrievalBudgets(
            @Value("${" + PREFIX + "base-branch-topk:" + DEFAULT_BASE_BRANCH_TOPK + "}") int baseBranchTopK,
            @Value("${" + PREFIX + "base-final-topk:" + DEFAULT_BASE_FINAL_TOPK + "}") int baseFinalTopK,
            @Value("${" + PREFIX + "expanded-branch-topk:" + DEFAULT_EXPANDED_BRANCH_TOPK + "}")
            int expandedBranchTopK,
            @Value("${" + PREFIX + "expanded-final-topk:" + DEFAULT_EXPANDED_FINAL_TOPK + "}")
            int expandedFinalTopK,
            @Value("${" + PREFIX + "base-parent-limit:" + DEFAULT_BASE_PARENT_LIMIT + "}") int baseParentLimit,
            @Value("${" + PREFIX + "expanded-parent-limit:" + DEFAULT_EXPANDED_PARENT_LIMIT + "}")
            int expandedParentLimit,
            @Value("${" + PREFIX + "base-child-chars:" + DEFAULT_BASE_CHILD_CHARS + "}") long baseChildCharBudget,
            @Value("${" + PREFIX + "expanded-child-chars:" + DEFAULT_EXPANDED_CHILD_CHARS + "}")
            long expandedChildCharBudget,
            @Value("${" + PREFIX + "parent-chars:" + DEFAULT_PARENT_CHARS + "}") long parentCharBudget) {
        // 启动时配置非法直接失败，与热更新时保留旧值不同
        this.snapshot = new Snapshot(baseBranchTopK, baseFinalTopK, expandedBranchTopK, expandedFinalTopK,
                baseParentLimit, expandedParentLimit, baseChildCharBudget, expandedChildCharBudget,
                parentCharBudget);
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    /** 配置中心推送后重新读取；变更键不一定是规范写法，故不按键过滤，值未变则不做任何事。 */
    @EventListener(EnvironmentChangeEvent.class)
    public void onEnvironmentChange() {
        if (environment != null) {
            reload(environment);
        }
    }

    /** 按当前环境重新读取并校验；返回是否发生替换。 */
    boolean reload(Environment env) {
        Snapshot current = snapshot;
        Snapshot next;
        try {
            next = new Snapshot(
                    readInt(env, "base-branch-topk", DEFAULT_BASE_BRANCH_TOPK),
                    readInt(env, "base-final-topk", DEFAULT_BASE_FINAL_TOPK),
                    readInt(env, "expanded-branch-topk", DEFAULT_EXPANDED_BRANCH_TOPK),
                    readInt(env, "expanded-final-topk", DEFAULT_EXPANDED_FINAL_TOPK),
                    readInt(env, "base-parent-limit", DEFAULT_BASE_PARENT_LIMIT),
                    readInt(env, "expanded-parent-limit", DEFAULT_EXPANDED_PARENT_LIMIT),
                    readLong(env, "base-child-chars", DEFAULT_BASE_CHILD_CHARS),
                    readLong(env, "expanded-child-chars", DEFAULT_EXPANDED_CHILD_CHARS),
                    readLong(env, "parent-chars", DEFAULT_PARENT_CHARS));
        } catch (RuntimeException e) {
            // 含无法解析为数字的值（ConversionFailedException）与校验失败
            log.warn("qa retrieval budgets refresh rejected, keeping {}: {}", current, e.getMessage());
            return false;
        }
        if (next.equals(current)) {
            return false;
        }
        snapshot = next;
        log.info("qa retrieval budgets refreshed: {} -> {}", current, next);
        return true;
    }

    private static int readInt(Environment env, String key, int defaultValue) {
        return env.getProperty(PREFIX + key, Integer.class, defaultValue);
    }

    private static long readLong(Environment env, String key, long defaultValue) {
        return env.getProperty(PREFIX + key, Long.class, defaultValue);
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public StageBudget base() {
        return snapshot.base();
    }

    public StageBudget expanded() {
        return snapshot.expanded();
    }

    public long parentCharBudget() {
        return snapshot.parentCharBudget();
    }
}
