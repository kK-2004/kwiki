package com.kwiki.graph.persistence;

import com.kwiki.graph.CommunityMembership;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.rag.retrieval.EntityLinkingStatus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GraphSnapshotValidationServiceTest {

    @Test
    void allChecksPassForCompleteSnapshotAndReportIsJsonRoundTrippable() {
        Fixture fixture = completeFixture();
        GraphSnapshotValidationReport report = new GraphSnapshotValidationService()
                .validate(fixture.input());

        assertThat(report.valid()).isTrue();
        assertThat(report.failures()).isEmpty();
        assertThat(report.toChecklist().valid()).isTrue();

        GraphSnapshotValidationReport parsed = GraphSnapshotValidationReport
                .fromJson(report.toJson());
        assertThat(parsed).isEqualTo(report);
        assertThat(parsed.valid()).isTrue();
        assertThat(GraphSnapshotValidationReport.fromJson(null)).isNull();
    }

    @Test
    void emptyGraphAndEmptyEntitySetsAreLegalResults() {
        Fixture fixture = completeFixture();
        String chunkId = sourceChunkId(0);
        fixture.esObservations.put(chunkId, new EntityMappingObservation(
                chunkId, List.of(), "entity-linking-v1", EntityLinkingStatus.READY));
        fixture.extractedEntityIds.put(chunkId, List.of());
        fixture.graphMentions.put(chunkId, List.of());
        fixture.inputEntityIds = List.of();
        fixture.memberships.clear();
        fixture.summaries.clear();
        fixture.embeddingDimensions.clear();

        GraphSnapshotValidationReport report = new GraphSnapshotValidationService()
                .validate(fixture.input());

        assertThat(report.valid()).isTrue();
        assertThat(report.nonEmptyCommunityCount()).isZero();
        assertThat(report.checkedSources()).isEqualTo(1);
        assertThat(report.readySources()).isEqualTo(1);
        assertThat(report.memberEntityCount()).isZero();
    }

    @Test
    void watermarkGapIsReportedAndBlocksReady() {
        Fixture fixture = completeFixture();
        fixture.manifest = manifest(false);
        GraphSnapshotValidationReport report = new GraphSnapshotValidationService()
                .validate(fixture.input());

        assertThat(report.valid()).isFalse();
        assertThat(report.failures())
                .singleElement()
                .satisfies(check -> {
                    assertThat(check.name())
                            .isEqualTo(GraphSnapshotValidationReport.EVENT_WATERMARK_CONTIGUOUS);
                    assertThat(check.detail()).contains("gap=present");
                });
    }

    @Test
    void pendingEsEntityFieldBlocksReadyWithoutFakingCoverage() {
        Fixture fixture = completeFixture();
        fixture.esObservations.put(sourceChunkId(0), new EntityMappingObservation(
                sourceChunkId(0), List.of(), "entity-linking-v1", EntityLinkingStatus.PENDING));

        GraphSnapshotValidationReport report = new GraphSnapshotValidationService()
                .validate(fixture.input());

        assertThat(report.valid()).isFalse();
        assertThat(report.failures()).extracting("name").contains(
                GraphSnapshotValidationReport.ENTITY_FIELDS_READY);
        assertThat(report.readySources()).isZero();
    }

    @Test
    void divergentEntityIdsBetweenEsExtractionAndGraphBlockReady() {
        Fixture fixture = completeFixture();
        fixture.extractedEntityIds.put(sourceChunkId(0), List.of("e_1"));

        GraphSnapshotValidationReport report = new GraphSnapshotValidationService()
                .validate(fixture.input());

        assertThat(report.valid()).isFalse();
        assertThat(report.failures()).extracting("name").contains(
                GraphSnapshotValidationReport.ENTITY_MAPPING_CONSISTENT);
    }

    @Test
    void incompleteMembershipBlocksReady() {
        Fixture fixture = completeFixture();
        fixture.memberships.remove(0);

        GraphSnapshotValidationReport report = new GraphSnapshotValidationService()
                .validate(fixture.input());

        assertThat(report.valid()).isFalse();
        assertThat(report.failures()).extracting("name").contains(
                GraphSnapshotValidationReport.MEMBERSHIP_COMPLETE);
    }

    @Test
    void missingSummaryOrWrongEmbeddingDimensionBlocksReady() {
        Fixture missingSummary = completeFixture();
        missingSummary.summaries.clear();
        assertThat(new GraphSnapshotValidationService().validate(missingSummary.input()).valid())
                .isFalse();

        Fixture wrongDimension = completeFixture();
        wrongDimension.embeddingDimensions.put("c_1", 512);
        GraphSnapshotValidationReport report = new GraphSnapshotValidationService()
                .validate(wrongDimension.input());
        assertThat(report.valid()).isFalse();
        assertThat(report.failures()).extracting("name").contains(
                GraphSnapshotValidationReport.EMBEDDINGS_COMPLETE);
    }

    @Test
    void configIdentityMismatchAndUnreadableRefreshBlockReady() {
        Fixture mismatch = completeFixture();
        mismatch.observedIdentity = new GraphSnapshotValidationService.ConfigIdentity(
                4, "entity-linking-v1", "summary-model", "prompt-v1", "embedding-model", 1024);
        assertThat(new GraphSnapshotValidationService().validate(mismatch.input()).valid())
                .isFalse();

        Fixture unreadable = completeFixture();
        unreadable.refreshReadable = false;
        GraphSnapshotValidationReport report = new GraphSnapshotValidationService()
                .validate(unreadable.input());
        assertThat(report.valid()).isFalse();
        assertThat(report.failures()).extracting("name").contains(
                GraphSnapshotValidationReport.REFRESH_READABLE);
    }

    @Test
    void reportRejectsInvalidCounts() {
        assertThatThrownBy(() -> new GraphSnapshotValidationReport(
                1, 1, -1, 0, 0, 0, 0, 0, 0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** 构造一个单来源、带两个实体和一个社区的完整快照观察集合。 */
    private static final class Fixture {
        long kbId = 7;
        long graphVersion = 42;
        GraphSourceManifest manifest = manifest(true);
        Map<String, EntityMappingObservation> esObservations = new HashMap<>();
        Map<String, List<String>> extractedEntityIds = new HashMap<>();
        Map<String, List<String>> graphMentions = new HashMap<>();
        List<String> inputEntityIds;
        List<CommunityMembership> memberships = new ArrayList<>();
        Map<String, CommunitySummary> summaries = new HashMap<>();
        Map<String, Integer> embeddingDimensions = new HashMap<>();
        GraphSnapshotValidationService.ConfigIdentity expectedIdentity =
                new GraphSnapshotValidationService.ConfigIdentity(
                        3, "entity-linking-v1", "summary-model", "prompt-v1",
                        "embedding-model", 1024);
        GraphSnapshotValidationService.ConfigIdentity observedIdentity = expectedIdentity;
        boolean refreshReadable = true;

        Fixture() {
            String chunkId = sourceChunkId(0);
            esObservations.put(chunkId, new EntityMappingObservation(
                    chunkId, List.of("e_1", "e_2"), "entity-linking-v1",
                    EntityLinkingStatus.READY));
            extractedEntityIds.put(chunkId, List.of("e_1", "e_2"));
            graphMentions.put(chunkId, List.of("e_1", "e_2"));
            inputEntityIds = List.of("e_1", "e_2");
            memberships.add(new CommunityMembership(kbId, graphVersion, "e_1", "c_1"));
            memberships.add(new CommunityMembership(kbId, graphVersion, "e_2", "c_1"));
            summaries.put("c_1", new CommunitySummary("主题", "概述", List.of("k"),
                    List.of(), List.of(), List.of(), List.of()));
            embeddingDimensions.put("c_1", 1024);
        }

        GraphSnapshotValidationService.Input input() {
            return new GraphSnapshotValidationService.Input(kbId, graphVersion, manifest,
                    List.copyOf(esObservations.values()), Map.copyOf(extractedEntityIds),
                    Map.copyOf(graphMentions), inputEntityIds, List.copyOf(memberships),
                    Map.copyOf(summaries), Map.copyOf(embeddingDimensions),
                    expectedIdentity, observedIdentity, refreshReadable);
        }
    }

    private static Fixture completeFixture() {
        return new Fixture();
    }

    private static GraphSourceManifest manifest(boolean contiguous) {
        GraphSourceChunk source = source(0);
        return new GraphSourceManifestBuilder().build(11, 7, 3, "entity-linking-v1",
                5, 2, 27, contiguous, List.of(source),
                Map.of(source.sourceChunkId(), GraphSourceReadiness.READY));
    }

    private static GraphSourceChunk source(int index) {
        return new GraphSourceChunk(7, "PAGE", 9, 4L, 2, 3,
                "parser-1", "chunker-1", "chunk-" + (index + 1),
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
    }

    private static String sourceChunkId(int index) {
        return source(index).sourceChunkId();
    }
}
