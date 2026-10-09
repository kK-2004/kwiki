package com.kwiki.rag.tool;

import java.util.List;
import java.util.Map;

/** 单个 agent 工具的代码侧执行器：由 {@link AgentToolDispatcher} 调用，读写 {@link EvidencePool}。 */
public interface AgentToolExecutor {

    String name();

    Outcome execute(AgentToolArguments arguments, ToolExecutionContext context);

    /** 成功结果：新增证据的引用、降级项、满足 tool-result-v2 的整数统计。 */
    record Outcome(List<String> evidenceRefs, List<String> degradations, Map<String, Object> stats) {
        public Outcome {
            evidenceRefs = List.copyOf(evidenceRefs);
            degradations = List.copyOf(degradations);
            stats = Map.copyOf(stats);
        }
    }

    /** 预期内的工具失败：转成 ERROR 结果，不中止 run。 */
    final class ToolFailure extends RuntimeException {
        private final String code;

        public ToolFailure(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
