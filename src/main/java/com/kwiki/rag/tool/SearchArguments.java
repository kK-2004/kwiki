package com.kwiki.rag.tool;

import com.kwiki.rag.retrieval.RetrievalStrategy;

import java.util.List;

public record SearchArguments(List<String> queries, RetrievalStrategy strategy, int topK) {
    public SearchArguments {
        queries = List.copyOf(queries);
    }

    public String signature() {
        return strategy + ":" + topK + ":" + queries.stream().sorted().toList();
    }
}
