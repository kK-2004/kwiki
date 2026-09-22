package com.kwiki.infrastructure.arcadedb;

import com.kwiki.graph.config.ArcadeDbProperties;

import java.util.HashMap;
import java.util.Map;

/** 在指定业务数据库中执行可重入的 ArcadeDB schema 初始化。 */
public final class ArcadeDbSchemaInitializer {

    private final ArcadeDbHttpAdapter client;
    private final ArcadeDbProperties properties;

    public ArcadeDbSchemaInitializer(ArcadeDbHttpAdapter client, ArcadeDbProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    public ArcadeDbSchemaInitializationResult initialize() {
        int commands = 0;
        for (String statement : ArcadeDbSchemaDefinition.types()) {
            client.command(properties.database(), statement, Map.of(),
                    ArcadeDbTimeoutKind.BATCH_WRITE);
            commands++;
        }
        for (String statement : ArcadeDbSchemaDefinition.indexes()) {
            client.command(properties.database(), statement, Map.of(),
                    ArcadeDbTimeoutKind.BATCH_WRITE);
            commands++;
        }
        Map<String, Object> params = new HashMap<>();
        params.put("name", ArcadeDbSchemaDefinition.SCHEMA_VERSION_NAME);
        params.put("version", ArcadeDbSchemaDefinition.SCHEMA_VERSION);
        client.command(properties.database(), ArcadeDbSchemaDefinition.schemaVersionUpsert(), params,
                ArcadeDbTimeoutKind.BATCH_WRITE);
        return new ArcadeDbSchemaInitializationResult(ArcadeDbSchemaDefinition.SCHEMA_VERSION, commands + 1);
    }
}
