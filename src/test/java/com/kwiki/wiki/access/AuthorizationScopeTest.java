package com.kwiki.wiki.access;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationScopeTest {

    @Test
    void memberScopeIncludesOnlyAccessibleKnowledgeBases() {
        AuthorizationScope scope = new AuthorizationScope(
                7L, false, Set.of(1L, 3L), Map.of(1L, 2L, 3L, 1L));
        assertThat(scope.includes(1L)).isTrue();
        assertThat(scope.includes(3L)).isTrue();
        assertThat(scope.includes(2L)).isFalse();
    }

    @Test
    void superuserScopeIncludesEverything() {
        AuthorizationScope scope = new AuthorizationScope(1L, true, Set.of(), Map.of());
        assertThat(scope.includes(42L)).isTrue();
    }

    @Test
    void scopeIsImmutableAgainstCallerMutations() {
        Set<Long> kbIds = new java.util.HashSet<>(Set.of(1L));
        Map<Long, Long> versions = new HashMap<>(Map.of(1L, 1L));
        AuthorizationScope scope = new AuthorizationScope(7L, false, kbIds, versions);

        kbIds.add(2L);
        versions.put(1L, 99L);

        assertThat(scope.includes(2L)).as("mutating the source set must not widen scope").isFalse();
        assertThat(scope.kbVersions()).as("captured version must stay at resolution-time value")
                .isEqualTo(Map.of(1L, 1L));
        assertThat(scope.isStale(kb -> 1L)).as("no real change: not stale").isFalse();
        assertThat(scope.isStale(kb -> 2L)).as("real membership change: stale").isTrue();
    }

    @Test
    void staleDetectionComparesPerKnowledgeBaseVersions() {
        AuthorizationScope scope = new AuthorizationScope(
                7L, false, Set.of(1L, 3L), Map.of(1L, 5L, 3L, 2L));
        assertThat(scope.isStale(kb -> kb == 1L ? 5L : 2L)).as("no change anywhere").isFalse();
        assertThat(scope.isStale(kb -> kb == 3L ? 3L : 5L)).as("kb 3 advanced").isTrue();
        assertThat(scope.isStale(kb -> 1L)).as("versions never go backwards").isFalse();
    }
}
