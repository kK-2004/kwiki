package com.kwiki.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EvalDatasetLoaderTest {

    final EvalDatasetLoader loader = new EvalDatasetLoader(new ObjectMapper());

    @Test
    void loadsCasesSkippingBlankLinesAndSelectsNestedTiers() {
        var cases = loader.load(new ClassPathResource("eval/loader/valid.jsonl"));
        assertThat(cases).extracting(EvalCase::id).containsExactly("t-1", "t-2", "t-3");
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q50)).extracting(EvalCase::id)
                .containsExactly("t-1");
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q100)).extracting(EvalCase::id)
                .containsExactly("t-1", "t-2");
        assertThat(EvalDatasetLoader.select(cases, EvalDatasetSize.Q200)).hasSize(3);
        assertThat(cases.get(0).keywords()).containsExactly(java.util.List.of("10,000吨", "1万吨"));
    }

    @Test
    void duplicateIdsAreRejected() {
        assertThatThrownBy(() -> loader.load(new ClassPathResource("eval/loader/duplicate.jsonl")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate eval case id: t-1");
    }

    @Test
    void invalidCaseReportsLineNumber() {
        assertThatThrownBy(() -> loader.load(new ClassPathResource("eval/loader/invalid.jsonl")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("line 1");
    }

    @Test
    void datasetSizeLabelsAndNames() {
        assertThat(EvalDatasetSize.parse("100q")).isEqualTo(EvalDatasetSize.Q100);
        assertThat(EvalDatasetSize.Q200.datasetName()).isEqualTo("kwiki-rag-200q");
        assertThatThrownBy(() -> EvalDatasetSize.parse("300q")).isInstanceOf(IllegalArgumentException.class);
    }
}
