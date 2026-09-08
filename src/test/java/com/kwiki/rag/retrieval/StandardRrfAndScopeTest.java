package com.kwiki.rag.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.infrastructure.elasticsearch.EsScopeFilterBuilder;
import com.kwiki.wiki.access.AuthorizationScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exact RRF formula and ordering semantics plus scope-filter fixtures:
 * score = Σ 1/(k + rank) with rank starting at one; duplicates merge; ties break
 * by best rank, BM25 rank, vector rank, then chunk key; superuser/empty/member
 * scopes produce their exact Elasticsearch filter shapes.
 */
class StandardRrfAndScopeTest {

    @Test
    void chunkInBothBranchesScoresExactlySumOfReciprocalRanks() {
        // chunk A ranks 5th in BM25 and 2nd in vector, rankConstant 60 -> 1/65 + 1/62
        List<StandardRrfFusion.FusedChunk> fused = StandardRrfFusion.fuse(List.of(
                new StandardRrfFusion.RankedList("BM25",
                        List.of("B1", "B2", "B3", "B4", "A", "B6")),
                new StandardRrfFusion.RankedList("VECTOR",
                        List.of("V1", "A", "V3", "V4", "V5", "V6"))), 60);

        StandardRrfFusion.FusedChunk chunkA = fused.stream()
                .filter(chunk -> chunk.chunkKey().equals("A")).findFirst().orElseThrow();
        double expected = 1.0 / 65 + 1.0 / 62;
        assertThat(chunkA.score()).isEqualTo(expected);
    }

    @Test
    void singleListScoresUseRankPlusConstant() {
        List<StandardRrfFusion.FusedChunk> fused = StandardRrfFusion.fuse(List.of(
                new StandardRrfFusion.RankedList("BM25", List.of("X", "Y"))), 60);
        assertThat(fused.get(0).score()).isEqualTo(1.0 / 61);
        assertThat(fused.get(1).score()).isEqualTo(1.0 / 62);
    }

    @Test
    void decomposedQueriesAccumulateAcrossAllLists() {
        List<StandardRrfFusion.FusedChunk> fused = StandardRrfFusion.fuse(List.of(
                new StandardRrfFusion.RankedList("BM25", List.of("X")),
                new StandardRrfFusion.RankedList("VECTOR", List.of("X")),
                new StandardRrfFusion.RankedList("BM25", List.of("X"))), 60);
        assertThat(fused.get(0).score()).isEqualTo(3.0 / 61);
    }

    @Test
    void sameChunkInMultipleListsAppearsOnceWithAllRanks() {
        List<StandardRrfFusion.FusedChunk> fused = StandardRrfFusion.fuse(List.of(
                new StandardRrfFusion.RankedList("BM25", List.of("X", "Y")),
                new StandardRrfFusion.RankedList("VECTOR", List.of("Y", "X"))), 60);
        assertThat(fused).extracting(StandardRrfFusion.FusedChunk::chunkKey)
                .containsExactly("X", "Y");
        assertThat(fused.get(0).bestRankByBranch()).isEqualTo(Map.of("BM25", 1, "VECTOR", 2));
    }

    @Test
    void equalScoresTieBreakDeterministically() {
        // both chunks appear once at rank 1 in different branches -> equal scores
        List<StandardRrfFusion.FusedChunk> fused = StandardRrfFusion.fuse(List.of(
                new StandardRrfFusion.RankedList("BM25", List.of("Z")),
                new StandardRrfFusion.RankedList("VECTOR", List.of("Z")),
                new StandardRrfFusion.RankedList("BM25", List.of("A")),
                new StandardRrfFusion.RankedList("VECTOR", List.of("A"))), 60);
        // Z: 1/61 + 1/61? no: rank1+rank1 = 2/61; A: 1/61+1/61 same -> tie
        assertThat(fused.get(0).score()).isEqualTo(fused.get(1).score());
        // tie broken by best rank (equal), bm25 rank (equal), vector rank (equal), key
        assertThat(fused).extracting(StandardRrfFusion.FusedChunk::chunkKey)
                .containsExactly("A", "Z");
    }

    @Test
    void missingOrEmptyListsProduceEmptyFusion() {
        assertThat(StandardRrfFusion.fuse(List.of(), 60)).isEmpty();
        assertThat(StandardRrfFusion.fuse(List.of(
                new StandardRrfFusion.RankedList("BM25", List.of())), 60)).isEmpty();
    }



    @Test
    void memberScopeProducesExactTermsFilterFixture() {
        var query = EsScopeFilterBuilder.build(ScopeFilter.from(new AuthorizationScope(7L, false, Set.of(3L, 1L), Map.of())));
        String json = co.elastic.clients.json.JsonpUtils.toJsonString(
                query, new co.elastic.clients.json.jackson.JacksonJsonpMapper());
        assertThat(json).isEqualTo("{\"terms\":{\"kbId\":[1,3]}}");
    }

    @Test
    void pageScopeKeepsDirectlySharedPagesAndExcludesOtherPageChunks() {
        var query = EsScopeFilterBuilder.build(ScopeFilter.from(new AuthorizationScope(
                7L, false, Set.of(3L), Map.of(), Set.of(42L))));
        String json = co.elastic.clients.json.JsonpUtils.toJsonString(
                query, new co.elastic.clients.json.jackson.JacksonJsonpMapper());
        assertThat(json).contains("resourceType", "resourceId", "42", "kbId", "3");
        assertThat(json).contains("minimum_should_match");
    }

    @Test
    void superuserScopeUsesMatchAll() {
        var query = EsScopeFilterBuilder.build(ScopeFilter.from(new AuthorizationScope(1L, true, Set.of(), Map.of())));
        String json = co.elastic.clients.json.JsonpUtils.toJsonString(
                query, new co.elastic.clients.json.jackson.JacksonJsonpMapper());
        assertThat(json).contains("match_all").doesNotContain("terms");
    }

    @Test
    void emptyScopeUsesMatchNone() {
        var query = EsScopeFilterBuilder.build(ScopeFilter.from(new AuthorizationScope(7L, false, Set.of(), Map.of())));
        String json = co.elastic.clients.json.JsonpUtils.toJsonString(
                query, new co.elastic.clients.json.jackson.JacksonJsonpMapper());
        assertThat(json).contains("match_none");
    }

    @Test
    void budgetsRejectOutOfRangeRequests() {
        RetrievalBudgets budgets = new RetrievalBudgets(50, 40, 8, 3, 24000,
                java.time.Duration.ofSeconds(5));
        budgets.validateRequest(8, 24000);
        assertThatThrownBy(() -> budgets.validateRequest(9, 24000))
                .hasMessageContaining("parent count out of range");
        assertThatThrownBy(() -> budgets.validateRequest(8, 30000))
                .hasMessageContaining("context budget out of range");
        assertThatThrownBy(() -> new RetrievalBudgets(0, 40, 8, 3, 24000,
                java.time.Duration.ofSeconds(5))).hasMessageContaining("topk");
        assertThatThrownBy(() -> new RetrievalBudgets(50, 40, 8, 4, 24000,
                java.time.Duration.ofSeconds(5))).hasMessageContaining("subqueries");
    }

    @Test
    void evalFixtureLoadsWithThresholds() throws Exception {
        try (var in = getClass().getResourceAsStream("/retrieval/eval-cases.json")) {
            Map<String, Object> fixture = new ObjectMapper().readValue(in, Map.class);
            assertThat(fixture.get("thresholds")).isNotNull();
            assertThat((List<?>) fixture.get("cases")).hasSize(5);
        }
    }
}
