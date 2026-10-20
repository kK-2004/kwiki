package com.kwiki.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 仓库测试集的数据质量门禁：规模、嵌套、题型配额、关键词可在原文找到且不出现在题干中。 */
class EvalDatasetFileTest {

    static final Map<String, int[]> QUOTAS = new LinkedHashMap<>();
    static {
        QUOTAS.put("无关无解问", new int[]{4, 8, 16});
        QUOTAS.put("事实性问题", new int[]{10, 21, 42});
        QUOTAS.put("多跳推理问题", new int[]{8, 15, 30});
        QUOTAS.put("总结性问题", new int[]{9, 17, 35});
        QUOTAS.put("多文档信息整合问题", new int[]{10, 21, 42});
        QUOTAS.put("多文档对比问题", new int[]{6, 11, 22});
        QUOTAS.put("多文档时间序列问题", new int[]{3, 7, 13});
    }

    static List<EvalCase> cases;
    static Map<Integer, String> documents = new HashMap<>();

    record Entry(String file, String title, String domain, int docId) { }

    @BeforeAll
    static void load() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        cases = new EvalDatasetLoader(mapper).load();
        String root = "eval/corpus/dragonball-zh/";
        List<Entry> manifest;
        try (InputStream in = new ClassPathResource(root + "manifest.json").getInputStream()) {
            manifest = mapper.readValue(in, new TypeReference<List<Entry>>() { });
        }
        for (Entry entry : manifest) {
            try (InputStream in = new ClassPathResource(root + entry.file()).getInputStream()) {
                documents.put(entry.docId(), KeywordMatcher.normalize(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            }
        }
    }

    @Test
    void datasetsAreNestedWithExactSizes() {
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q50)).hasSize(50);
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q100)).hasSize(100);
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q200)).hasSize(200);
    }

    @Test
    void categoryQuotasMatchTheSamplingPlan() {
        EvalDatasetSize[] sizes = EvalDatasetSize.values();
        for (int i = 0; i < sizes.length; i++) {
            Map<String, Long> counts = EvalDatasetLoader.select(cases, sizes[i]).stream()
                    .collect(Collectors.groupingBy(EvalCase::category, Collectors.counting()));
            for (var quota : QUOTAS.entrySet()) {
                assertThat(counts.getOrDefault(quota.getKey(), 0L))
                        .as(sizes[i].label() + " " + quota.getKey())
                        .isEqualTo(quota.getValue()[i]);
            }
        }
    }

    @Test
    void negativesAreExactlyTheUnanswerableCategory() {
        assertThat(cases).allSatisfy(evalCase ->
                assertThat(evalCase.negative()).isEqualTo("无关无解问".equals(evalCase.category())));
    }

    @Test
    void everyKeywordGroupAppearsVerbatimInItsSourceDocuments() {
        for (EvalCase evalCase : cases) {
            if (evalCase.negative()) continue;
            String corpus = evalCase.sourceDocIds().stream()
                    .map(id -> documents.getOrDefault(id, "")).collect(Collectors.joining("\u0000"));
            for (List<String> group : evalCase.keywords()) {
                assertThat(group.stream().map(KeywordMatcher::normalize).anyMatch(corpus::contains))
                        .as(evalCase.id() + " keyword group " + group + " must appear in source documents")
                        .isTrue();
            }
        }
    }

    @Test
    void noKeywordAliasAppearsInTheQuery() {
        for (EvalCase evalCase : cases) {
            String query = KeywordMatcher.normalize(evalCase.query());
            for (List<String> group : evalCase.keywords()) {
                for (String alias : group) {
                    assertThat(query.contains(KeywordMatcher.normalize(alias)))
                            .as(evalCase.id() + " alias '" + alias + "' must not appear in the query")
                            .isFalse();
                }
            }
        }
    }

    @Test
    void multiDocumentCasesCoverEverySourceDocument() {
        for (EvalCase evalCase : cases) {
            if (evalCase.negative() || evalCase.sourceDocIds().size() < 2) continue;
            for (Integer docId : evalCase.sourceDocIds()) {
                String document = documents.getOrDefault(docId, "");
                boolean covered = evalCase.keywords().stream().anyMatch(group ->
                        group.stream().map(KeywordMatcher::normalize).anyMatch(document::contains));
                assertThat(covered).as(evalCase.id() + " must have a keyword from doc " + docId).isTrue();
            }
        }
    }
}
