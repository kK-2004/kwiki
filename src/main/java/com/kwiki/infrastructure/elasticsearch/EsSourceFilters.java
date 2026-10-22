package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch.core.search.SourceConfig;

/**
 * 读取 chunk / 社区文档时排除 dense_vector：应用从不读取 _source 中的向量，
 * 而 Top-50 命中的向量 JSON 可达数 MB，经远程链路下载会把单次检索拖到十秒级。
 */
final class EsSourceFilters {

    static final String VECTOR_FIELD = "vector";

    private EsSourceFilters() {
    }

    static SourceConfig withoutVector() {
        return SourceConfig.of(source -> source.filter(filter -> filter.excludes(VECTOR_FIELD)));
    }
}
