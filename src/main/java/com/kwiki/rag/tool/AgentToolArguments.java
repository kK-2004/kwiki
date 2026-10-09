package com.kwiki.rag.tool;

import com.kwiki.rag.retrieval.RetrievalStrategy;

import java.util.List;

/** 经 schema 与批级规则校验后的带类型工具参数；`signature()` 用于 callId 幂等判定。 */
public sealed interface AgentToolArguments {

    /** 检索深度：映射 QaRetrievalBudgets 的 base() / expanded()。 */
    enum Range {
        BASE,
        EXPANDED
    }

    enum RewriteKind {
        CONVERSATIONAL,
        EXPANSION,
        DECOMPOSITION,
        FEEDBACK
    }

    String signature();

    record SearchChunks(List<String> queries, RetrievalStrategy strategy, Range range)
            implements AgentToolArguments {
        public SearchChunks {
            queries = List.copyOf(queries);
        }

        @Override
        public String signature() {
            return strategy + ":" + range + ":" + queries.stream().sorted().toList();
        }
    }

    /** childChunkKeys 为空表示使用证据池里的全部子块。 */
    record ExpandParents(List<String> childChunkKeys, Range range) implements AgentToolArguments {
        public ExpandParents {
            childChunkKeys = List.copyOf(childChunkKeys);
        }

        @Override
        public String signature() {
            return range + ":" + childChunkKeys.stream().sorted().toList();
        }
    }

    /** query 为 null 表示使用证据池的当前 query。 */
    record GraphExpand(String query) implements AgentToolArguments {
        @Override
        public String signature() {
            return String.valueOf(query);
        }
    }

    record RewriteQuery(RewriteKind mode) implements AgentToolArguments {
        @Override
        public String signature() {
            return mode.name();
        }
    }
}
