package com.kwiki.graph.build.stage;

import com.kwiki.graph.GraphCapacityGuard;
import com.kwiki.graph.GraphWriteRequest;
import com.kwiki.graph.KnowledgeGraphStore;
import com.kwiki.graph.UnweightedGraphProjectionBuilder;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.build.RetryPolicy;
import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.config.GraphProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** PROJECTING 阶段：容量守卫 → 无权投影 → 写入 ArcadeDB（按 graphVersion 隔离）。 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ProjectionStage implements GraphBuildStage {

    private final GraphCapacityGuard capacityGuard = new GraphCapacityGuard();
    private final GraphProperties graphProperties;
    private final KnowledgeGraphStore store;
    private final GraphBuildProperties buildProperties;

    public ProjectionStage(GraphProperties graphProperties, KnowledgeGraphStore store,
                           GraphBuildProperties buildProperties) {
        this.graphProperties = graphProperties;
        this.store = store;
        this.buildProperties = buildProperties;
    }

    @Override
    public void execute(RunContext ctx, RunArtifacts artifacts) {
        var run = ctx.run();
        RetryPolicy retry = RetryPolicy.of(buildProperties.maxAttempts(),
                buildProperties.retryBaseDelay());

        long relationSources = artifacts.relations.stream()
                .mapToLong(relation -> relation.sourceRefs().size()).sum();
        GraphCapacityGuard.Counts counts = new GraphCapacityGuard.Counts(
                artifacts.entities.size(), artifacts.relations.size(), relationSources, 0);
        GraphCapacityGuard.Decision decision = capacityGuard.check(counts,
                graphProperties.capacity());
        if (!decision.allowed()) {
            throw GraphBuildFailure.permanent(decision.reason(), capacitySummary(decision.reason()));
        }

        artifacts.projection = UnweightedGraphProjectionBuilder.build(run.kbId(),
                run.graphVersion(), artifacts.entities, artifacts.relations);
        retry.run(() -> store.write(new GraphWriteRequest(run.kbId(), run.graphVersion(),
                artifacts.sources, artifacts.entities, artifacts.relations,
                String.valueOf(run.id()), ctx.fencingToken())));
    }

    private String capacitySummary(String reason) {
        var capacity = graphProperties.capacity();
        return switch (reason == null ? "" : reason) {
            case "CAPACITY_EXCEEDED_ENTITIES" -> "实体数超过上限 " + capacity.maxEntities();
            case "CAPACITY_EXCEEDED_RELATIONS" -> "关系数超过上限 " + capacity.maxRelations();
            case "CAPACITY_EXCEEDED_RELATION_SOURCES" ->
                    "关系来源数超过上限 " + capacity.maxRelationSources();
            case "CAPACITY_EXCEEDED_COMMUNITIES" -> "社区数超过上限 " + capacity.maxCommunities();
            default -> "图容量超过配置上限";
        };
    }
}
