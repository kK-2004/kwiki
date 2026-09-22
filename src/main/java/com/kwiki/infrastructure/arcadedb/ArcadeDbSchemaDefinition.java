package com.kwiki.infrastructure.arcadedb;

import java.util.List;

/**
 * ArcadeDB schema 的固定定义。所有 DDL 只在适配器内维护，业务端口不接触
 * SQL/Cypher；重复执行使用 IF NOT EXISTS 或幂等 UPSERT。
 */
final class ArcadeDbSchemaDefinition {

    static final int SCHEMA_VERSION = 1;
    static final String SCHEMA_VERSION_NAME = "kwiki-graph";

    private ArcadeDbSchemaDefinition() {
    }

    static List<String> types() {
        return List.of(
                "CREATE DOCUMENT TYPE SchemaVersion IF NOT EXISTS",
                "CREATE VERTEX TYPE Document IF NOT EXISTS",
                "CREATE VERTEX TYPE Chunk IF NOT EXISTS",
                "CREATE VERTEX TYPE Entity IF NOT EXISTS",
                "CREATE VERTEX TYPE RelationEvidence IF NOT EXISTS",
                "CREATE VERTEX TYPE Community IF NOT EXISTS",
                "CREATE EDGE TYPE CONTAINS IF NOT EXISTS",
                "CREATE EDGE TYPE MENTIONS IF NOT EXISTS",
                "CREATE EDGE TYPE RELATES_TO IF NOT EXISTS",
                "CREATE EDGE TYPE SUPPORTS IF NOT EXISTS",
                "CREATE EDGE TYPE SOURCE_CHUNK IF NOT EXISTS",
                "CREATE EDGE TYPE IN_COMMUNITY IF NOT EXISTS",
                "CREATE EDGE TYPE CONNECTED IF NOT EXISTS");
    }

    static List<String> indexes() {
        return List.of(
                "CREATE INDEX Entity[kbId, graphVersion, entityId] UNIQUE",
                "CREATE INDEX Entity[kbId, graphVersion, communityId] NOTUNIQUE",
                "CREATE INDEX Community[kbId, graphVersion, communityId] UNIQUE",
                "CREATE INDEX Chunk[kbId, graphVersion, sourceChunkId] UNIQUE",
                "CREATE INDEX RelationEvidence[kbId, graphVersion, relationId, evidenceId] UNIQUE");
    }

    static String schemaVersionUpsert() {
        return "UPDATE SchemaVersion SET name = :name, version = :version "
                + "UPSERT WHERE name = :name";
    }
}
