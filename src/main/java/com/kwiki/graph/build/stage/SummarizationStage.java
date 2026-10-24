package com.kwiki.graph.build.stage;

import com.kwiki.graph.CommunityMembership;
import com.kwiki.graph.CommunityRepresentativeSelector;
import com.kwiki.graph.CommunitySummary;
import com.kwiki.graph.CommunitySummaryInput;
import com.kwiki.graph.CommunitySummaryValidator;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphRelation;
import com.kwiki.graph.GraphSourceRef;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.build.RunProgress;
import com.kwiki.graph.build.ProgressThrottle;
import com.kwiki.graph.build.RetryPolicy;
import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.plugin.CommunitySummarizerPlugin;
import com.kwiki.graph.plugin.CommunitySummarizerRegistry;
import com.kwiki.rag.retrieval.ChunkHit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * SUMMARIZING 阶段：按社区挑代表 → 摘要插件 → 摘要校验。
 * 只有两端都在同一社区内的关系参与该社区的摘要。
 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class SummarizationStage implements GraphBuildStage {

    private final CommunitySummarizerRegistry summarizers;
    private final GraphBuildProperties buildProperties;

    public SummarizationStage(CommunitySummarizerRegistry summarizers,
                              GraphBuildProperties buildProperties) {
        this.summarizers = summarizers;
        this.buildProperties = buildProperties;
    }

    @Override
    public void execute(RunContext ctx, RunArtifacts artifacts) {
        GraphBuildRunRecord run = ctx.run();
        CommunitySummarizerPlugin summarizer = summarizers.require(run.summarizer());
        RetryPolicy retry = RetryPolicy.of(buildProperties.maxAttempts(),
                buildProperties.retryBaseDelay());

        Map<String, GraphEntity> entitiesById = artifacts.entities.stream()
                .collect(Collectors.toMap(GraphEntity::entityId, entity -> entity,
                        (a, b) -> a, LinkedHashMap::new));
        Map<String, List<CommunityMembership>> byCommunity = artifacts.detection.memberships()
                .stream().collect(Collectors.groupingBy(CommunityMembership::communityId));

        long communitiesTotal = byCommunity.size();
        long summarized = 0;
        // 抽取阶段已完成：沿用其「已抽取 / 缓存命中」，不再用实体数覆盖、也不把命中数清零
        long sourcesTotal = artifacts.sources.size();
        long cacheHits = artifacts.extractionCacheHits;
        ProgressThrottle throttle = new ProgressThrottle();
        ctx.progress(new RunProgress(sourcesTotal, sourcesTotal, cacheHits, communitiesTotal, 0));

        for (Map.Entry<String, List<CommunityMembership>> entry : byCommunity.entrySet()) {
            ctx.throwIfCancelled();
            String communityId = entry.getKey();
            List<GraphEntity> communityEntities = entry.getValue().stream()
                    .map(member -> entitiesById.get(member.entityId()))
                    .filter(entity -> entity != null).toList();
            Set<String> memberIds = communityEntities.stream()
                    .map(GraphEntity::entityId).collect(Collectors.toSet());
            List<GraphRelation> communityRelations = artifacts.relations.stream()
                    .filter(relation -> memberIds.contains(relation.sourceEntityId())
                            && memberIds.contains(relation.targetEntityId()))
                    .toList();

            CommunitySummaryInput input = CommunityRepresentativeSelector.select(run.kbId(),
                    run.graphVersion(), communityId, communityEntities, communityRelations,
                    artifacts.sources, joinedContent(communityRelations, artifacts),
                    summarizer.promptVersion());
            CommunitySummary summary;
            try {
                summary = retry.run(() -> summarizer.summarize(input), ctx.modelAttempts());
                CommunitySummaryValidator.requireValid(input, summary);
            } catch (IllegalArgumentException invalid) {
                throw GraphBuildFailure.permanent("SUMMARY_INVALID",
                        "社区摘要未通过校验：" + invalid.getMessage());
            }
            artifacts.summaries.put(communityId, summary);
            summarized++;
            if (throttle.due() || summarized == communitiesTotal) {
                ctx.progress(new RunProgress(sourcesTotal, sourcesTotal, cacheHits,
                        communitiesTotal, summarized));
            }
        }
    }

    /** 拼接社区关系引文涉及的来源块正文（按 chunkKey 确定顺序）。 */
    private static String joinedContent(List<GraphRelation> communityRelations,
                                        RunArtifacts artifacts) {
        Set<String> sourceChunkIds = new HashSet<>();
        for (GraphRelation relation : communityRelations) {
            for (GraphSourceRef ref : relation.sourceRefs()) {
                sourceChunkIds.add(ref.sourceChunkId());
            }
        }
        List<ChunkHit> hits = new ArrayList<>();
        for (String sourceChunkId : sourceChunkIds) {
            ChunkHit hit = artifacts.childrenBySourceChunkId.get(sourceChunkId);
            if (hit != null) {
                hits.add(hit);
            }
        }
        hits.sort(Comparator.comparing(ChunkHit::chunkKey));
        return hits.stream().map(ChunkHit::content)
                .collect(Collectors.joining("\n\n"));
    }
}
