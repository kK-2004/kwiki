package com.kwiki.infrastructure.arcadedb;

import com.kwiki.graph.config.ArcadeDbProperties;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ArcadeDbGraphDataPurgerTest {

    private final ArcadeDbHttpAdapter client = mock(ArcadeDbHttpAdapter.class);
    private final ArcadeDbProperties properties = mock(ArcadeDbProperties.class);

    @Test
    void 先截断边再截断顶点且保留SchemaVersion() {
        when(properties.database()).thenReturn("kwiki");
        when(client.databaseExists("kwiki")).thenReturn(true);
        List<String> purged = new ArcadeDbGraphDataPurger(client, properties).purgeAll();

        ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
        verify(client, times(11)).command(eq("kwiki"), statements.capture(), eq(Map.of()), eq(ArcadeDbTimeoutKind.BATCH_WRITE));
        assertThat(statements.getAllValues().get(0)).isEqualTo("TRUNCATE TYPE CONTAINS UNSAFE");
        assertThat(statements.getAllValues().getLast()).isEqualTo("TRUNCATE TYPE Document UNSAFE");
        assertThat(statements.getAllValues().indexOf("TRUNCATE TYPE IN_COMMUNITY UNSAFE"))
                .isLessThan(statements.getAllValues().indexOf("TRUNCATE TYPE Community UNSAFE"));
        assertThat(String.join(",", statements.getAllValues())).doesNotContain("SchemaVersion");
        assertThat(purged).hasSize(11);
    }

    @Test
    void 库不存在时视为已空() {
        when(properties.database()).thenReturn("kwiki");
        when(client.databaseExists("kwiki")).thenReturn(false);
        assertThat(new ArcadeDbGraphDataPurger(client, properties).purgeAll()).isEmpty();
        verify(client, never()).command(anyString(), anyString(), anyMap(), any(ArcadeDbTimeoutKind.class));
    }
}
