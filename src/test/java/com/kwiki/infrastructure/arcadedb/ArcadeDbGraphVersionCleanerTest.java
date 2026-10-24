package com.kwiki.infrastructure.arcadedb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kwiki.graph.config.ArcadeDbProperties;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 按 (kbId, graphVersion) 分批删除，直到计数为 0；只碰本版本数据。 */
class ArcadeDbGraphVersionCleanerTest {

    private static ArcadeDbResponse count(long value) {
        return new ArcadeDbResponse(200, "{\"result\":[{\"c\":" + value + "}]}");
    }

    @Test
    void deletesEachVertexTypeInBatchesUntilEmpty() {
        ArcadeDbHttpAdapter client = mock(ArcadeDbHttpAdapter.class);
        ArcadeDbProperties properties = mock(ArcadeDbProperties.class);
        when(properties.database()).thenReturn("kwiki");
        when(client.databaseExists("kwiki")).thenReturn(true);
        // 每种类型剩余量：Entity 需要两轮，其余一轮或本来为空
        Map<String, List<Long>> remaining = new HashMap<>(Map.of(
                "RelationEvidence", new ArrayList<>(List.of(3L, 0L)),
                "Entity", new ArrayList<>(List.of(8000L, 3000L, 0L)),
                "Chunk", new ArrayList<>(List.of(2L, 0L)),
                "Community", new ArrayList<>(List.of(0L))));
        when(client.query(eq("kwiki"), eq("sql"), anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(2);
            String type = sql.substring(sql.indexOf("FROM ") + 5, sql.indexOf(" WHERE"));
            return count(remaining.get(type).remove(0));
        });
        List<String> deletes = new ArrayList<>();
        when(client.command(eq("kwiki"), anyString(), anyMap(), eq(ArcadeDbTimeoutKind.BATCH_WRITE)))
                .thenAnswer(invocation -> {
                    deletes.add(invocation.getArgument(1));
                    Map<String, Object> params = invocation.getArgument(2);
                    assertThat(params).containsEntry("kbId", 9L).containsEntry("graphVersion", 3L);
                    return new ArcadeDbResponse(200, "{}");
                });

        new ArcadeDbGraphVersionCleaner(client, properties, new ObjectMapper()).deleteGraphVersion(9, 3);

        assertThat(deletes).containsExactly(
                "DELETE FROM RelationEvidence WHERE kbId = :kbId AND graphVersion = :graphVersion LIMIT 5000",
                "DELETE FROM Entity WHERE kbId = :kbId AND graphVersion = :graphVersion LIMIT 5000",
                "DELETE FROM Entity WHERE kbId = :kbId AND graphVersion = :graphVersion LIMIT 5000",
                "DELETE FROM Chunk WHERE kbId = :kbId AND graphVersion = :graphVersion LIMIT 5000");
    }

    @Test
    void missingDatabaseIsAlreadyClean() {
        ArcadeDbHttpAdapter client = mock(ArcadeDbHttpAdapter.class);
        ArcadeDbProperties properties = mock(ArcadeDbProperties.class);
        when(properties.database()).thenReturn("kwiki");
        when(client.databaseExists("kwiki")).thenReturn(false);

        new ArcadeDbGraphVersionCleaner(client, properties, new ObjectMapper()).deleteGraphVersion(9, 3);

        verify(client, never()).command(anyString(), anyString(), anyMap(), any());
    }

    @Test
    void unparsableCountFailsInsteadOfLoopingForever() {
        ArcadeDbHttpAdapter client = mock(ArcadeDbHttpAdapter.class);
        ArcadeDbProperties properties = mock(ArcadeDbProperties.class);
        when(properties.database()).thenReturn("kwiki");
        when(client.databaseExists("kwiki")).thenReturn(true);
        when(client.query(eq("kwiki"), eq("sql"), anyString(), anyMap()))
                .thenReturn(new ArcadeDbResponse(200, "not json"));

        assertThatThrownBy(() -> new ArcadeDbGraphVersionCleaner(client, properties, new ObjectMapper())
                .deleteGraphVersion(9, 3))
                .isInstanceOf(IllegalStateException.class);
    }
}
