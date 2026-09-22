package com.kwiki.graph;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConservativeGraphEntityResolverTest {

    @Test
    void reusesOnlyExactContextAndResolverGeneration() {
        FakeRegistry registry = new FakeRegistry();
        ConservativeGraphEntityResolver resolver = new ConservativeGraphEntityResolver(registry);
        GraphEntity candidate = new GraphEntity(null, "锁", GraphEntityType.CONCEPT, List.of("lock"));

        GraphEntity first = resolver.resolve(1, List.of(candidate), "分布式锁", "resolver-v1").getFirst();
        GraphEntity same = resolver.resolve(1, List.of(candidate), "分布式锁", "resolver-v1").getFirst();
        GraphEntity ambiguous = resolver.resolve(1, List.of(candidate), "门锁", "resolver-v1").getFirst();

        assertThat(first.entityId()).isEqualTo(same.entityId());
        assertThat(ambiguous.entityId()).isNotEqualTo(first.entityId());
    }

    private static final class FakeRegistry implements GraphEntityRegistryPort {
        private final Map<String, GraphEntity> values = new HashMap<>();
        private long nextId = 1;

        @Override
        public GraphEntity findOrRegister(long kbId, GraphEntity candidate,
                                          String contextFingerprint, String resolverVersion) {
            String key = kbId + ":" + candidate.canonicalName() + ":"
                    + candidate.entityType() + ":" + contextFingerprint + ":" + resolverVersion;
            return values.computeIfAbsent(key, ignored -> candidate.withEntityId("e-" + nextId++));
        }
    }
}
