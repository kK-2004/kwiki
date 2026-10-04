package com.kwiki.infrastructure.arcadedb;

import com.kwiki.graph.config.ArcadeDbProperties;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ArcadeDbSchemaInitializerTest {

    private final ArcadeDbHttpAdapter client = mock(ArcadeDbHttpAdapter.class);

    private static ArcadeDbProperties properties(String database) {
        return new ArcadeDbProperties(URI.create("http://arcadedb.test:2480"), database, "t",
                Duration.ofSeconds(3), Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofSeconds(3), 8, new ArcadeDbProperties.Tls(false, true), "kwiki_leiden", null);
    }

    @Test
    void 建类型属性索引且只执行一次() {
        when(client.databaseExists("kwiki")).thenReturn(true);
        var initializer = new ArcadeDbSchemaInitializer(client, properties("kwiki"));

        initializer.ensureInitialized();
        initializer.ensureInitialized();

        var order = inOrder(client);
        order.verify(client).command(eq("kwiki"), eq("CREATE VERTEX TYPE Entity IF NOT EXISTS"),
                anyMap(), any());
        order.verify(client).command(eq("kwiki"), eq("CREATE PROPERTY Entity.entityId IF NOT EXISTS STRING"),
                anyMap(), any());
        order.verify(client).command(eq("kwiki"),
                eq("CREATE INDEX IF NOT EXISTS ON Entity (kbId, graphVersion, entityId) UNIQUE"), anyMap(), any());
        int statements = ArcadeDbSchemaDefinition.types().size() + ArcadeDbSchemaDefinition.properties().size()
                + ArcadeDbSchemaDefinition.indexes().size() + 1;
        verify(client, times(statements)).command(eq("kwiki"), anyString(), anyMap(), any());
    }

    @Test
    void 库不存在时明确报错_应用不建库() {
        when(client.databaseExists("kwiki")).thenReturn(false);
        var initializer = new ArcadeDbSchemaInitializer(client, properties("kwiki"));

        assertThatThrownBy(initializer::ensureInitialized)
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("管理员");
        verify(client, never()).command(anyString(), anyString(), anyMap(), any());
    }

    @Test
    void 失败后下次调用会重试() {
        when(client.databaseExists("kwiki")).thenThrow(new IllegalStateException("down")).thenReturn(true);
        var initializer = new ArcadeDbSchemaInitializer(client, properties("kwiki"));

        assertThatThrownBy(initializer::ensureInitialized).hasMessage("down");
        assertThat(initializer.ensureInitialized().schemaVersion()).isEqualTo(ArcadeDbSchemaDefinition.SCHEMA_VERSION);
    }
}
