package com.kwiki.usage;

import com.kwiki.rag.orchestration.AgenticLimits;
import com.kwiki.rag.orchestration.RunContext;
import com.kwiki.wiki.access.AuthorizationScope;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ModelUsageContextTest {

    static RunContext run(long userId) {
        return new RunContext("req-9", new AuthorizationScope(userId, false, Set.of(1L), Map.of()),
                kb -> 0L, AgenticLimits.defaults(), Map.of());
    }

    @Test
    void withoutScopeOrRunItIsBackgroundSystem() {
        assertThat(ModelUsageContext.resolve())
                .isEqualTo(new ModelUsageContext.Resolved(UsageSource.BACKGROUND, 0L, null, null));
    }

    @Test
    void boundRunIsOnlineWithItsUserAndRequestId() throws Exception {
        var run = run(42L);
        try (var bound = run.bind()) {
            assertThat(ModelUsageContext.resolve())
                    .isEqualTo(new ModelUsageContext.Resolved(UsageSource.ONLINE, 42L, "req-9", null));
        }
    }

    @Test
    void runMarkedAsEvalReportsEval() throws Exception {
        var run = run(7L);
        run.usageSource(UsageSource.EVAL);
        try (var bound = run.bind()) {
            assertThat(ModelUsageContext.resolve().source()).isEqualTo(UsageSource.EVAL);
        }
    }

    @Test
    void explicitScopeWinsAndFallsBackToRunUser() throws Exception {
        var run = run(42L);
        try (var bound = run.bind();
             var scope = ModelUsageContext.open(UsageSource.BACKGROUND, null, UsageSteps.INDEX_EMBEDDING)) {
            assertThat(ModelUsageContext.resolve()).isEqualTo(new ModelUsageContext.Resolved(
                    UsageSource.BACKGROUND, 42L, "req-9", UsageSteps.INDEX_EMBEDDING));
        }
    }

    @Test
    void nestedScopesRestoreThePreviousOne() {
        try (var outer = ModelUsageContext.open(UsageSource.EVAL, 5L)) {
            try (var inner = ModelUsageContext.open(UsageSource.BACKGROUND, 0L, UsageSteps.INDEX_EMBEDDING)) {
                assertThat(ModelUsageContext.resolve().source()).isEqualTo(UsageSource.BACKGROUND);
            }
            assertThat(ModelUsageContext.resolve())
                    .isEqualTo(new ModelUsageContext.Resolved(UsageSource.EVAL, 5L, null, null));
        }
        assertThat(ModelUsageContext.resolve().source()).isEqualTo(UsageSource.BACKGROUND);
    }
}
