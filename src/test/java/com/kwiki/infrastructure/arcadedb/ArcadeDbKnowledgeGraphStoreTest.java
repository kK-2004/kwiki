package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.GraphEntity;
import com.kwiki.graph.GraphEntityType;
import com.kwiki.graph.GraphPredicate;
import com.kwiki.graph.GraphRelation;
import com.kwiki.graph.GraphSourceChunk;
import com.kwiki.graph.GraphSourceRef;
import com.kwiki.graph.GraphWriteRequest;
import com.kwiki.graph.config.ArcadeDbProperties;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/** 图投影批写：每批一个 sqlscript 请求、一个事务，语句仍是 UPSERT，参数按行加前缀。 */
class ArcadeDbKnowledgeGraphStoreTest {

    private record Call(String language, String script, Map<String, Object> params) {
    }

    private final ArcadeDbHttpAdapter client = mock(ArcadeDbHttpAdapter.class);

    private ArcadeDbKnowledgeGraphStore store() {
        ArcadeDbProperties properties = mock(ArcadeDbProperties.class);
        when(properties.database()).thenReturn("kwiki");
        return new ArcadeDbKnowledgeGraphStore(client, properties, new ObjectMapper(),
                mock(ArcadeDbSchemaInitializer.class));
    }

    @SuppressWarnings("unchecked")
    private List<Call> commands() {
        return mockingDetails(client).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("command"))
                .map(Invocation::getArguments)
                .map(args -> new Call((String) args[1], (String) args[2], (Map<String, Object>) args[3]))
                .toList();
    }

    private static GraphSourceChunk source(int i) {
        return new GraphSourceChunk(9L, "PAGE", 100L + i, 1L, 0L, 1L, "p1", "c1", "k" + i, "h" + i);
    }

    private static GraphEntity entity(int i) {
        return new GraphEntity("e" + i, "实体" + i, GraphEntityType.PRODUCT, List.of("别名" + i));
    }

    @Test
    void eachKindIsWrittenAsOneTransactionalScriptPerBatch() {
        GraphSourceChunk chunk = source(0);
        var relation = new GraphRelation("r1", "e0", GraphPredicate.USES, "e1", 0.8, List.of(
                new GraphSourceRef(chunk.sourceChunkId(), 0, 4),
                new GraphSourceRef(chunk.sourceChunkId(), 10, 20)));

        var result = store().write(new GraphWriteRequest(9L, 3L, List.of(chunk, source(1)),
                List.of(entity(0), entity(1), entity(2)), List.of(relation), "run-7", 4L));

        assertThat(result.sourceCount()).isEqualTo(2);
        assertThat(result.entityCount()).isEqualTo(3);
        assertThat(result.newRelationEvidenceCount()).isEqualTo(2);
        List<Call> calls = commands();
        // Chunk、Entity、RelationEvidence 各一个请求，而不是 2 + 3 + 2 个
        assertThat(calls).hasSize(3);
        assertThat(calls).allSatisfy(call -> {
            assertThat(call.language()).isEqualTo("sqlscript");
            assertThat(call.script()).startsWith("BEGIN;\n").endsWith("COMMIT;");
        });

        Call entities = calls.get(1);
        assertThat(entities.script()).contains("UPDATE Entity SET", "UPSERT WHERE");
        assertThat(entities.script().split("UPSERT WHERE")).hasSize(4);
        assertThat(entities.script())
                .contains(":p0_entityId", ":p2_entityId")
                .doesNotContain(":entityId", ":p3_");
        assertThat(entities.params())
                .containsEntry("p0_entityId", "e0")
                .containsEntry("p2_entityId", "e2")
                .containsEntry("p1_canonicalName", "实体1")
                .containsEntry("p2_aliases", List.of("别名2"))
                .containsEntry("p0_graphVersion", 3L);

        Call evidence = calls.get(2);
        assertThat(evidence.params())
                .containsEntry("p0_evidenceId", "r1:" + chunk.sourceChunkId() + ":0:4")
                .containsEntry("p1_evidenceId", "r1:" + chunk.sourceChunkId() + ":10:20")
                .containsEntry("p1_runId", "run-7")
                .containsEntry("p1_fencingToken", 4L);
    }

    @Test
    void largeKindsAreSplitAtFiveHundredStatementsPerScript() {
        var entities = IntStream.range(0, 1201).mapToObj(ArcadeDbKnowledgeGraphStoreTest::entity).toList();

        var result = store().write(new GraphWriteRequest(9L, 3L, List.of(), entities, List.of(), "run-7", 4L));

        assertThat(result.entityCount()).isEqualTo(1201);
        List<Call> calls = commands();
        assertThat(calls).extracting(call -> call.script().split("UPSERT WHERE").length - 1)
                .containsExactly(500, 500, 201);
        assertThat(calls.get(2).params()).containsEntry("p200_entityId", "e1200")
                .doesNotContainKey("p201_entityId");
    }

    @Test
    void emptyKindsSendNothing() {
        store().write(new GraphWriteRequest(9L, 3L, List.of(), List.of(), List.of(), "run-7", 4L));

        assertThat(commands()).isEmpty();
    }

    @Test
    void prefixingDoesNotTouchLongerNamesThatShareAPrefix() {
        var script = ArcadeDbKnowledgeGraphStore.batchScript(
                "UPDATE T SET a = :kb, b = :kbId UPSERT WHERE kbId = :kbId",
                List.of(Map.of("kb", 1, "kbId", 2), Map.of("kb", 3, "kbId", 4)));

        assertThat(script.script()).isEqualTo("BEGIN;\n"
                + "UPDATE T SET a = :p0_kb, b = :p0_kbId UPSERT WHERE kbId = :p0_kbId;\n"
                + "UPDATE T SET a = :p1_kb, b = :p1_kbId UPSERT WHERE kbId = :p1_kbId;\n"
                + "COMMIT;");
        assertThat(script.parameters()).containsExactlyInAnyOrderEntriesOf(
                Map.of("p0_kb", 1, "p0_kbId", 2, "p1_kb", 3, "p1_kbId", 4));
    }
}
