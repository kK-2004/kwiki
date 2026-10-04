package com.kwiki.infrastructure.arcadedb;

import com.kwiki.graph.config.ArcadeDbProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 在业务库中执行可重入的 ArcadeDB schema 初始化。建库是只有 root 能执行的服务器
 * 命令，库需由管理员预建；不存在时明确报错。成功一次后进程内不再重复执行，失败则下次调用重试。
 */
public final class ArcadeDbSchemaInitializer {

    private final ArcadeDbHttpAdapter client;
    private final ArcadeDbProperties properties;
    private volatile ArcadeDbSchemaInitializationResult initialized;

    public ArcadeDbSchemaInitializer(ArcadeDbHttpAdapter client, ArcadeDbProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 首次调用执行初始化，之后直接返回缓存结果。 */
    public ArcadeDbSchemaInitializationResult ensureInitialized() {
        ArcadeDbSchemaInitializationResult result = initialized;
        if (result != null) {
            return result;
        }
        synchronized (this) {
            if (initialized == null) {
                initialized = initialize();
            }
            return initialized;
        }
    }

    public ArcadeDbSchemaInitializationResult initialize() {
        String database = properties.database();
        int commands = 0;
        if (!client.databaseExists(database)) {
            throw new IllegalStateException("ArcadeDB 库 " + database
                    + " 不存在或 token 无权访问：请由管理员（root）创建并授权");
        }
        for (var statements : java.util.List.of(ArcadeDbSchemaDefinition.types(),
                ArcadeDbSchemaDefinition.properties(), ArcadeDbSchemaDefinition.indexes())) {
            for (String statement : statements) {
                client.command(database, statement, Map.of(), ArcadeDbTimeoutKind.BATCH_WRITE);
                commands++;
            }
        }
        Map<String, Object> params = new HashMap<>();
        params.put("name", ArcadeDbSchemaDefinition.SCHEMA_VERSION_NAME);
        params.put("version", ArcadeDbSchemaDefinition.SCHEMA_VERSION);
        client.command(database, ArcadeDbSchemaDefinition.schemaVersionUpsert(), params,
                ArcadeDbTimeoutKind.BATCH_WRITE);
        return new ArcadeDbSchemaInitializationResult(ArcadeDbSchemaDefinition.SCHEMA_VERSION, commands + 1);
    }
}
