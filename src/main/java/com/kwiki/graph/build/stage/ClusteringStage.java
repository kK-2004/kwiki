package com.kwiki.graph.build.stage;

import com.kwiki.graph.CommunityDetectionInput;
import com.kwiki.graph.GraphCapacityGuard;
import com.kwiki.graph.build.GraphBuildFailure;
import com.kwiki.graph.build.RunArtifacts;
import com.kwiki.graph.build.RunContext;
import com.kwiki.graph.build.RetryPolicy;
import com.kwiki.graph.config.GraphBuildProperties;
import com.kwiki.graph.config.GraphClusteringProperties;
import com.kwiki.graph.config.GraphProperties;
import com.kwiki.graph.persistence.GraphBuildRunRecord;
import com.kwiki.graph.plugin.CommunityDetectorPlugin;
import com.kwiki.graph.plugin.CommunityDetectorRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/** CLUSTERING 阶段：在无权投影上执行社区检测插件，并校验社区数量上限。 */
@Component
@ConditionalOnProperty(name = "kwiki.graph.enabled", havingValue = "true")
public class ClusteringStage implements GraphBuildStage {

    private final GraphCapacityGuard capacityGuard = new GraphCapacityGuard();
    private final CommunityDetectorRegistry detectors;
    private final GraphProperties graphProperties;
    private final GraphClusteringProperties clustering;
    private final GraphBuildProperties buildProperties;

    public ClusteringStage(CommunityDetectorRegistry detectors,
                           GraphProperties graphProperties,
                           GraphClusteringProperties clustering,
                           GraphBuildProperties buildProperties) {
        this.detectors = detectors;
        this.graphProperties = graphProperties;
        this.clustering = clustering;
        this.buildProperties = buildProperties;
    }

    @Override
    public void execute(RunContext ctx, RunArtifacts artifacts) {
        GraphBuildRunRecord run = ctx.run();
        CommunityDetectorPlugin detector = detectors.require(run.communityDetector());
        RetryPolicy retry = RetryPolicy.of(buildProperties.maxAttempts(),
                buildProperties.retryBaseDelay());

        artifacts.detection = retry.run(() -> detector.detect(new CommunityDetectionInput(
                run.kbId(), run.graphVersion(), graphProperties.algorithmMode(),
                clustering.maxIterations(), clustering.resolution(),
                artifacts.projection.projectionHash(), String.valueOf(run.id()),
                ctx.fencingToken())));

        Set<String> communities = new HashSet<>();
        artifacts.detection.memberships().forEach(member -> communities.add(member.communityId()));
        if (communities.size() > buildProperties.maxSummariesPerRun()) {
            throw GraphBuildFailure.permanent("CAPACITY_EXCEEDED_COMMUNITIES",
                    "社区数超过摘要上限 " + buildProperties.maxSummariesPerRun());
        }
        GraphCapacityGuard.Decision decision = capacityGuard.check(
                new GraphCapacityGuard.Counts(0, 0, 0, communities.size()),
                graphProperties.capacity());
        if (!decision.allowed()) {
            throw GraphBuildFailure.permanent(decision.reason(),
                    "社区数超过上限 " + graphProperties.capacity().maxCommunities());
        }
    }
}
