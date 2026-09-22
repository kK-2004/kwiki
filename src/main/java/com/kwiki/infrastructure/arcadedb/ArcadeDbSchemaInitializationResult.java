package com.kwiki.infrastructure.arcadedb;

/** ArcadeDB schema 初始化结果。 */
public record ArcadeDbSchemaInitializationResult(int schemaVersion, int commandCount) {
}
