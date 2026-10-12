package com.kwiki.graph.model;

import com.kwiki.infrastructure.ai.registry.ModelPurpose;
import com.kwiki.testutil.StubModelRegistry;

import dev.langchain4j.model.chat.ChatModel;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 图构建模型端点：从模型目录按用途解析、缺省限流与可用性判定。 */
class GraphModelClientsTest {

    @Test
    void 两个用途都已分配时端点可用且模型名来自目录() {
        ChatModel extraction = mock(ChatModel.class);
        ChatModel summary = mock(ChatModel.class);
        GraphModelClients clients = new GraphModelClients(StubModelRegistry.empty()
                .withPurpose(ModelPurpose.GRAPH_EXTRACTION, "extract-model", extraction)
                .withPurpose(ModelPurpose.GRAPH_SUMMARY, "summary-model", summary));

        assertThat(clients.extraction().orElseThrow().modelName()).isEqualTo("extract-model");
        assertThat(clients.extraction().orElseThrow().model()).isSameAs(extraction);
        assertThat(clients.summary().orElseThrow().modelName()).isEqualTo("summary-model");
        assertThat(clients.missing("extraction")).isEmpty();
        assertThat(clients.missing("summary")).isEmpty();
    }

    @Test
    void provider未设并发上限时使用端点缺省限流且重试取provider() {
        GraphModelClients clients = new GraphModelClients(StubModelRegistry.empty()
                .withPurpose(ModelPurpose.GRAPH_EXTRACTION, "m", mock(ChatModel.class))
                .withPurpose(ModelPurpose.GRAPH_SUMMARY, "m", mock(ChatModel.class)));
        ModelEndpoint extraction = clients.extraction().orElseThrow();
        ModelEndpoint summary = clients.summary().orElseThrow();

        // 抽取 4 / 5.0，摘要 2 / 2.0；限流器跨调用共享
        assertThat(extraction.limiter().interval()).isEqualTo(Duration.ofMillis(200));
        assertThat(extraction.limiter().permits()).isEqualTo(4);
        assertThat(summary.limiter().interval()).isEqualTo(Duration.ofMillis(500));
        assertThat(summary.limiter().permits()).isEqualTo(2);
        assertThat(clients.extraction().orElseThrow().limiter()).isSameAs(extraction.limiter());
        assertThat(extraction.maxRetries()).isEqualTo(2);
    }

    @Test
    void 未分配时不可用并给出用途名() {
        GraphModelClients clients = new GraphModelClients(StubModelRegistry.empty());

        assertThat(clients.extraction()).isEmpty();
        assertThat(clients.summary()).isEmpty();
        assertThat(clients.missing("extraction")).singleElement().asString()
                .contains("GRAPH_EXTRACTION").contains("图谱抽取");
        assertThat(clients.missing("summary")).singleElement().asString().contains("GRAPH_SUMMARY");
    }
}
