package com.kwiki.graph.build.stage;

import com.kwiki.graph.CommunityDetectionResult;
import com.kwiki.graph.CommunityMembership;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.GraphAlgorithmMode;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphEntityType;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.build.RunProgress;
import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.persistence.GraphBuildStage;
import com.kwiki.graph.persistence.GraphBuildState;
import com.kwiki.graph.plugin.CommunitySummarizerPlugin;
import com.kwiki.graph.plugin.CommunitySummarizerRegistry;
import com.kwiki.rag.retrieval.ChunkHit;
import com.kwiki.rag.retrieval.EntityLinkingStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 摘要阶段：社区分组、代表选择与校验失败分类。 */
class SummarizationStageTest {

    private static GraphBuildRunRecord run() {
        return new GraphBuildRunRecord(12L, 9L, 7L, 5, "kwiki-chunks-v5", 6L,
                "kwiki-communities-v6-kb7", 42L, "kwiki-schema-1", "entity-linking-v1",
                8L, 2L, GraphBuildState.SUMMARIZING, GraphBuildStage.SUMMARIZING, 3L,
                "owner-a", null, 0L, "kwiki-extractor-1", "arcadedb-leiden-1",
                "kwiki-summarizer-1");
    }

    private static GraphBuildProperties buildProperties() {
        return new GraphBuildProperties(Duration.ofSeconds(10), 2, Duration.ofMinutes(2),
                1, Duration.ZERO, 100, 50);
    }

    private static ChunkHit hit(String key, String content) {
        return new ChunkHit(key, "kb7:page:1", 7L, "PAGE", 1L, 3L, "h", 0, content.length(),
                content, List.of(), null, List.of(), "entity-linking-v1",
                EntityLinkingStatus.PENDING);
    }

    private static RunArtifacts artifacts(CommunityDetectionResult detection,
                                          List<GraphEntity> entities,
                                          List<com.kwiki.graph.GraphRelation> relations) {
        RunArtifacts artifacts = new RunArtifacts();
        artifacts.detection = detection;
        artifacts.entities = entities;
        artifacts.relations = relations;
        artifacts.sources = List.of();
        artifacts.childrenBySourceChunkId.put("sc_1", hit("kb7:page:1:chunk:0",
                "Kwiki 与 ArcadeDB 构成图存储社区。"));
        return artifacts;
    }

    private static CommunitySummary summaryOf(String title) {
        return new CommunitySummary(title, "摘要正文", List.of("关键词"), List.of(),
                List.of(), List.of(), List.of());
    }

    @Test
    void 每个社区调用一次摘要插件并上报进度() {
        List<String> summarizedCommunities = new CopyOnWriteArrayList<>();
        CommunitySummarizerPlugin summarizer = new CommunitySummarizerPlugin() {
            @Override
            public String id() {
                return "kwiki-summarizer-1";
            }

            @Override
            public String label() {
                return "x";
            }

            @Override
            public String promptVersion() {
                return "community-summary-v1";
            }

            @Override
            public String modelName() {
                return "summary-model";
            }

            @Override
            public CommunitySummary summarize(com.kwiki.graph.CommunitySummaryInput input) {
                summarizedCommunities.add(input.communityId());
                return summaryOf("社区 " + input.communityId());
            }
        };
        SummarizationStage stage = new SummarizationStage(
                new CommunitySummarizerRegistry(List.of(summarizer)), buildProperties());
        CommunityDetectionResult detection = new CommunityDetectionResult(
                GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED, "25.1", List.of(
                new CommunityMembership(7L, 42L, "a", "c0"),
                new CommunityMembership(7L, 42L, "b", "c1")));
        RunArtifacts artifacts = artifacts(detection, List.of(
                new GraphEntity("a", "A", GraphEntityType.CONCEPT, List.of()),
                new GraphEntity("b", "B", GraphEntityType.CONCEPT, List.of())), List.of());
        List<RunProgress> progressValues = new CopyOnWriteArrayList<>();
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progressValues::add);

        stage.execute(ctx, artifacts);

        assertThat(summarizedCommunities).containsExactlyInAnyOrder("c0", "c1");
        assertThat(artifacts.summaries).containsKeys("c0", "c1");
        RunProgress last = progressValues.get(progressValues.size() - 1);
        assertThat(last.communitiesTotal()).isEqualTo(2);
        assertThat(last.communitiesSummarized()).isEqualTo(2);
    }

    @Test
    void 摘要校验失败为永久失败() {
        CommunitySummarizerPlugin summarizer = new CommunitySummarizerPlugin() {
            @Override
            public String id() {
                return "kwiki-summarizer-1";
            }

            @Override
            public String label() {
                return "x";
            }

            @Override
            public String promptVersion() {
                return "community-summary-v1";
            }

            @Override
            public String modelName() {
                return "summary-model";
            }

            @Override
            public CommunitySummary summarize(com.kwiki.graph.CommunitySummaryInput input) {
                // 引用未提供的来源 → CommunitySummaryValidator 失败
                return new CommunitySummary("标题", "正文", List.of(), List.of(), List.of(),
                        List.of(), List.of(new com.kwiki.graph.GraphSourceRef("sc_unknown", 0, 1)));
            }
        };
        SummarizationStage stage = new SummarizationStage(
                new CommunitySummarizerRegistry(List.of(summarizer)), buildProperties());
        CommunityDetectionResult detection = new CommunityDetectionResult(
                GraphAlgorithmMode.ARCADEDB_NATIVE_UNWEIGHTED, "25.1", List.of(
                new CommunityMembership(7L, 42L, "a", "c0")));
        RunArtifacts artifacts = artifacts(detection, List.of(
                new GraphEntity("a", "A", GraphEntityType.CONCEPT, List.of())), List.of());
        RunContext ctx = new RunContext(run(), "owner-a", 3L, () -> false, progress -> {
        });

        assertThatThrownBy(() -> stage.execute(ctx, artifacts))
                .isInstanceOfSatisfying(GraphBuildFailure.class, failure -> {
                    assertThat(failure.code()).isEqualTo("SUMMARY_INVALID");
                    assertThat(failure.retryable()).isFalse();
                });
    }
}
