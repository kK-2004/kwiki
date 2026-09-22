package com.kwiki.graph.persistence;

import com.kwiki.graph.CommunityMembership;
import com.kwiki.graph.CommunityMembershipValidator;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.rag.retrieval.EntityLinkingStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 快照 VALIDATING 阶段的统一校验：来源覆盖、成员完整、图/抽取/ES entityIds
 * 一致、实体字段 READY、摘要与向量完整、mapping/配置身份及刷新可读性。
 * 空图与成功空实体集合是合法结果；报告只携带计数与状态，不携带正文或实体名。
 */
public final class GraphSnapshotValidationService {

    public GraphSnapshotValidationReport validate(Input input) {
        requireNonNull(input);
        Objects.requireNonNull(input.manifest(), "来源清单不能为空");

        List<GraphSnapshotValidationReport.Check> checks = new ArrayList<>();
        checks.add(sourceCoverage(input));
        checks.add(watermarkContiguous(input));
        checks.add(membership(input));
        checks.add(entityFieldsReady(input));
        checks.add(entityMappingConsistent(input));
        checks.add(summaries(input));
        checks.add(embeddings(input));
        checks.add(configIdentity(input));
        checks.add(refreshReadable(input));
        return new GraphSnapshotValidationReport(
                input.kbId(), input.graphVersion(),
                input.manifest().entries().size(),
                readySourceCount(input),
                input.inputEntityIds() == null ? 0 : input.inputEntityIds().size(),
                distinctMembers(input),
                distinctCommunities(input).size(),
                input.summaries() == null ? 0 : input.summaries().size(),
                embeddedCommunityCount(input),
                checks);
    }

    private GraphSnapshotValidationReport.Check sourceCoverage(Input input) {
        GraphSourceManifest manifest = input.manifest();
        long notReady = manifest.entries().stream()
                .filter(entry -> entry.readiness() != GraphSourceReadiness.READY)
                .count();
        boolean versionMatches = manifest.entries().stream()
                .allMatch(entry -> manifest.entityLinkingVersion()
                        .equals(entry.entityLinkingVersion()));
        if (notReady == 0 && versionMatches) {
            return GraphSnapshotValidationReport.Check.pass(
                    GraphSnapshotValidationReport.SOURCE_COVERAGE,
                    "entries=" + manifest.entries().size());
        }
        return GraphSnapshotValidationReport.Check.fail(
                GraphSnapshotValidationReport.SOURCE_COVERAGE,
                "entries=" + manifest.entries().size() + ",notReady=" + notReady
                        + ",versionMatches=" + versionMatches);
    }

    private GraphSnapshotValidationReport.Check watermarkContiguous(Input input) {
        GraphSourceManifest manifest = input.manifest();
        if (manifest.contiguousEventWatermark()) {
            return GraphSnapshotValidationReport.Check.pass(
                    GraphSnapshotValidationReport.EVENT_WATERMARK_CONTIGUOUS,
                    "watermark=" + manifest.eventWatermark());
        }
        return GraphSnapshotValidationReport.Check.fail(
                GraphSnapshotValidationReport.EVENT_WATERMARK_CONTIGUOUS,
                "watermark=" + manifest.eventWatermark() + ",gap=present");
    }

    private GraphSnapshotValidationReport.Check membership(Input input) {
        try {
            CommunityMembershipValidator.requireComplete(
                    input.inputEntityIds(), input.memberships(),
                    input.kbId(), input.graphVersion());
            return GraphSnapshotValidationReport.Check.pass(
                    GraphSnapshotValidationReport.MEMBERSHIP_COMPLETE,
                    "entities=" + (input.inputEntityIds() == null
                            ? 0 : input.inputEntityIds().size()));
        } catch (IllegalArgumentException failure) {
            return GraphSnapshotValidationReport.Check.fail(
                    GraphSnapshotValidationReport.MEMBERSHIP_COMPLETE,
                    failure.getMessage());
        }
    }

    private GraphSnapshotValidationReport.Check entityFieldsReady(Input input) {
        Map<String, EntityMappingObservation> observed = bySource(input.esObservations());
        String expectedVersion = input.manifest().entityLinkingVersion();
        long notReady = 0;
        for (GraphSourceManifestEntry entry : input.manifest().entries()) {
            EntityMappingObservation item = observed.get(
                    entry.sourceChunk().sourceChunkId());
            if (item == null || item.status() != EntityLinkingStatus.READY
                    || !expectedVersion.equals(item.entityLinkingVersion())) {
                notReady++;
            }
        }
        if (notReady == 0) {
            return GraphSnapshotValidationReport.Check.pass(
                    GraphSnapshotValidationReport.ENTITY_FIELDS_READY,
                    "sources=" + input.manifest().entries().size());
        }
        return GraphSnapshotValidationReport.Check.fail(
                GraphSnapshotValidationReport.ENTITY_FIELDS_READY,
                "notReady=" + notReady);
    }

    private GraphSnapshotValidationReport.Check entityMappingConsistent(Input input) {
        Map<String, EntityMappingObservation> observed = bySource(input.esObservations());
        Map<String, List<String>> extracted = input.extractedEntityIds() == null
                ? Map.of() : input.extractedEntityIds();
        Map<String, List<String>> mentions = input.graphMentionEntityIds() == null
                ? Map.of() : input.graphMentionEntityIds();
        long mismatched = 0;
        for (GraphSourceManifestEntry entry : input.manifest().entries()) {
            String sourceChunkId = entry.sourceChunk().sourceChunkId();
            List<String> es = observed.containsKey(sourceChunkId)
                    ? observed.get(sourceChunkId).entityIds() : null;
            if (es == null
                    || !normalize(es).equals(normalize(extracted.get(sourceChunkId)))
                    || !normalize(es).equals(normalize(mentions.get(sourceChunkId)))) {
                mismatched++;
            }
        }
        if (mismatched == 0) {
            return GraphSnapshotValidationReport.Check.pass(
                    GraphSnapshotValidationReport.ENTITY_MAPPING_CONSISTENT,
                    "sources=" + input.manifest().entries().size());
        }
        return GraphSnapshotValidationReport.Check.fail(
                GraphSnapshotValidationReport.ENTITY_MAPPING_CONSISTENT,
                "mismatched=" + mismatched);
    }

    private GraphSnapshotValidationReport.Check summaries(Input input) {
        Set<String> communities = distinctCommunities(input);
        Map<String, CommunitySummary> summaries = input.summaries() == null
                ? Map.of() : input.summaries();
        long missing = communities.stream()
                .filter(communityId -> summaries.get(communityId) == null)
                .count();
        if (missing == 0) {
            return GraphSnapshotValidationReport.Check.pass(
                    GraphSnapshotValidationReport.SUMMARIES_COMPLETE,
                    "communities=" + communities.size());
        }
        return GraphSnapshotValidationReport.Check.fail(
                GraphSnapshotValidationReport.SUMMARIES_COMPLETE,
                "communities=" + communities.size() + ",missing=" + missing);
    }

    private GraphSnapshotValidationReport.Check embeddings(Input input) {
        Set<String> communities = distinctCommunities(input);
        Map<String, Integer> dimensions = input.observedEmbeddingDimensions() == null
                ? Map.of() : input.observedEmbeddingDimensions();
        Integer expected = input.expectedIdentity() == null
                ? null : input.expectedIdentity().embeddingDimensions();
        long missing = 0;
        for (String communityId : communities) {
            Integer observed = dimensions.get(communityId);
            if (observed == null || expected == null || !observed.equals(expected)) {
                missing++;
            }
        }
        if (missing == 0) {
            return GraphSnapshotValidationReport.Check.pass(
                    GraphSnapshotValidationReport.EMBEDDINGS_COMPLETE,
                    "communities=" + communities.size() + ",dimensions=" + expected);
        }
        return GraphSnapshotValidationReport.Check.fail(
                GraphSnapshotValidationReport.EMBEDDINGS_COMPLETE,
                "communities=" + communities.size() + ",invalid=" + missing
                        + ",expectedDimensions=" + expected);
    }

    private GraphSnapshotValidationReport.Check configIdentity(Input input) {
        ConfigIdentity expected = input.expectedIdentity();
        ConfigIdentity observed = input.observedIdentity();
        if (expected != null && observed != null && expected.equals(observed)) {
            return GraphSnapshotValidationReport.Check.pass(
                    GraphSnapshotValidationReport.MAPPING_CONFIG_IDENTITY,
                    "mappingSchemaVersion=" + expected.mappingSchemaVersion()
                            + ",entityLinkingVersion=" + expected.entityLinkingVersion());
        }
        return GraphSnapshotValidationReport.Check.fail(
                GraphSnapshotValidationReport.MAPPING_CONFIG_IDENTITY,
                "expected=" + expected + ",observed=" + observed);
    }

    private GraphSnapshotValidationReport.Check refreshReadable(Input input) {
        if (input.refreshReadable()) {
            return GraphSnapshotValidationReport.Check.pass(
                    GraphSnapshotValidationReport.REFRESH_READABLE,
                    "refresh=readable");
        }
        return GraphSnapshotValidationReport.Check.fail(
                GraphSnapshotValidationReport.REFRESH_READABLE,
                "refresh=not-readable");
    }

    private static Map<String, EntityMappingObservation> bySource(
            List<EntityMappingObservation> observations) {
        Map<String, EntityMappingObservation> bySource = new HashMap<>();
        for (EntityMappingObservation item : observations == null
                ? List.<EntityMappingObservation>of() : observations) {
            bySource.put(item.sourceChunkId(), item);
        }
        return bySource;
    }

    private static long readySourceCount(Input input) {
        Map<String, EntityMappingObservation> observed = bySource(input.esObservations());
        return input.manifest().entries().stream().filter(entry -> {
            EntityMappingObservation item = observed.get(entry.sourceChunk().sourceChunkId());
            return item != null && item.status() == EntityLinkingStatus.READY;
        }).count();
    }

    private static long distinctMembers(Input input) {
        Set<String> members = new HashSet<>();
        for (CommunityMembership membership : input.memberships() == null
                ? List.<CommunityMembership>of() : input.memberships()) {
            members.add(membership.entityId());
        }
        return members.size();
    }

    private static Set<String> distinctCommunities(Input input) {
        Set<String> communities = new HashSet<>();
        for (CommunityMembership membership : input.memberships() == null
                ? List.<CommunityMembership>of() : input.memberships()) {
            communities.add(membership.communityId());
        }
        return communities;
    }

    private static long embeddedCommunityCount(Input input) {
        Integer expected = input.expectedIdentity() == null
                ? null : input.expectedIdentity().embeddingDimensions();
        Map<String, Integer> dimensions = input.observedEmbeddingDimensions() == null
                ? Map.of() : input.observedEmbeddingDimensions();
        return distinctCommunities(input).stream()
                .filter(communityId -> {
                    Integer observed = dimensions.get(communityId);
                    return observed != null && expected != null && observed.equals(expected);
                }).count();
    }

    private static List<String> normalize(List<String> entityIds) {
        return entityIds == null ? null : entityIds.stream()
                .filter(Objects::nonNull).distinct().sorted().toList();
    }

    private static void requireNonNull(Input input) {
        if (input == null) {
            throw new IllegalArgumentException("校验输入不能为空");
        }
    }

    /** 快照固定并与构建目标核对的配置身份。 */
    public record ConfigIdentity(int mappingSchemaVersion,
                                 String entityLinkingVersion,
                                 String summaryModel,
                                 String summaryPromptVersion,
                                 String embeddingModel,
                                 Integer embeddingDimensions) {
    }

    /** VALIDATING 阶段收集的全部观察值；各输入为空集合合法，缺失以检查失败表达。 */
    public record Input(long kbId,
                        long graphVersion,
                        GraphSourceManifest manifest,
                        List<EntityMappingObservation> esObservations,
                        Map<String, List<String>> extractedEntityIds,
                        Map<String, List<String>> graphMentionEntityIds,
                        List<String> inputEntityIds,
                        List<CommunityMembership> memberships,
                        Map<String, CommunitySummary> summaries,
                        Map<String, Integer> observedEmbeddingDimensions,
                        ConfigIdentity expectedIdentity,
                        ConfigIdentity observedIdentity,
                        boolean refreshReadable) {
    }
}
