package com.kwiki.infrastructure.elasticsearch;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.kwiki.indexing.gray.ReadRouting;
import com.kwiki.indexing.search.ElasticsearchIndexManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 读路由 → ES：一次查询同时覆盖全局别名与已切换灰度的物理索引。
 * 灰度索引的文档只保留该灰度的知识库；别名文档排除所有已切换到灰度的知识库。
 */
public final class EsReadRouting {

    private EsReadRouting() {
    }

    public static List<String> indices(ReadRouting routing) {
        List<String> indices = new ArrayList<>();
        indices.add(ElasticsearchIndexManager.ALIAS);
        indices.addAll(routing.kbIdsByGrayIndex().keySet().stream().sorted().toList());
        return indices;
    }

    public static Optional<Query> filter(ReadRouting routing) {
        if (routing.isEmpty()) {
            return Optional.empty();
        }
        List<FieldValue> grayIndices = routing.kbIdsByGrayIndex().keySet().stream().sorted()
                .map(FieldValue::of).toList();
        List<FieldValue> switchedKbs = longs(routing.switchedKbIds());
        return Optional.of(Query.of(query -> query.bool(bool -> {
            // 别名分支：不是灰度索引的文档，且知识库未切换到灰度
            bool.should(should -> should.bool(alias -> alias
                    .mustNot(not -> not.terms(terms -> terms.field("_index").terms(values -> values.value(grayIndices))))
                    .mustNot(not -> not.terms(terms -> terms.field("kbId").terms(values -> values.value(switchedKbs))))));
            // 灰度分支：每个灰度索引只保留其知识库
            for (Map.Entry<String, Set<Long>> entry : routing.kbIdsByGrayIndex().entrySet()) {
                List<FieldValue> kbs = longs(entry.getValue());
                bool.should(should -> should.bool(gray -> gray
                        .filter(filter -> filter.term(term -> term.field("_index").value(entry.getKey())))
                        .filter(filter -> filter.terms(terms -> terms.field("kbId").terms(values -> values.value(kbs))))));
            }
            return bool.minimumShouldMatch("1");
        })));
    }

    private static List<FieldValue> longs(Set<Long> values) {
        // 显式取 long 重载：Long 走 FieldValue.of(Object) 会成为 Any 变体
        return values.stream().sorted().map(value -> FieldValue.of(value.longValue())).toList();
    }
}
