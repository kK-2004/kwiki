package com.kwiki.graph;

import com.kwiki.graph.config.GraphProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphProjectionAndBudgetTest {

    @Test
    void projectionKeepsIsolatesAndDeduplicatesDirectionPredicateAndEvidence() {
        GraphEntity a = new GraphEntity("a", "A", GraphEntityType.CONCEPT, List.of());
        GraphEntity b = new GraphEntity("b", "B", GraphEntityType.CONCEPT, List.of());
        GraphEntity isolate = new GraphEntity("z", "Z", GraphEntityType.CONCEPT, List.of());
        GraphSourceRef ref = new GraphSourceRef("sc_1", 0, 1);
        GraphRelation first = new GraphRelation("r1", "a", GraphPredicate.RELATED_TO,
                "b", 1, List.of(ref));
        GraphRelation reverse = new GraphRelation("r2", "b", GraphPredicate.USES,
                "a", 1, List.of(ref));
        GraphRelation self = new GraphRelation("r3", "a", GraphPredicate.USES,
                "a", 1, List.of(ref));

        UnweightedGraphProjection projection = UnweightedGraphProjectionBuilder.build(1, 2,
                List.of(a, b, isolate), List.of(first, reverse, self));

        assertThat(projection.entityIds()).containsExactly("a", "b", "z");
        assertThat(projection.edges()).containsExactly(
                new UnweightedGraphProjection.Edge("a", "b"));
        assertThat(projection.projectionHash()).hasSize(64);
    }

    @Test
    void capacityGuardCountsRelationSourcesAndWarningsIndependently() {
        GraphProperties.Capacity capacity = GraphProperties.Capacity.defaults();
        GraphCapacityGuard guard = new GraphCapacityGuard();

        assertThat(guard.check(new GraphCapacityGuard.Counts(
                21_000, 100_000, 1_000_001, 1), capacity).allowed()).isFalse();
        assertThat(guard.check(new GraphCapacityGuard.Counts(
                20_000, 100_000, 2, 1), capacity).warning()).isTrue();
    }
}
