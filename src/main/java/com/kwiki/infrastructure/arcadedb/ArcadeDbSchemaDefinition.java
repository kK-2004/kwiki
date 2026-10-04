package com.kwiki.infrastructure.arcadedb;

import java.util.List;

/**
 * ArcadeDB schema 的固定定义。所有 DDL 只在适配器内维护，业务端口不接触
 * SQL/Cypher；重复执行使用 IF NOT EXISTS 或幂等 UPSERT。
 */
public final class ArcadeDbSchemaDefinition {

    public static final int SCHEMA_VERSION = 1;
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

    /** 建索引前 ArcadeDB 要求属性已定义；类型与写入参数（Java long / String）对应。 */
    static List<String> properties() {
        return List.of(
                "CREATE PROPERTY SchemaVersion.name IF NOT EXISTS STRING",
                "CREATE PROPERTY Entity.kbId IF NOT EXISTS LONG",
                "CREATE PROPERTY Entity.graphVersion IF NOT EXISTS LONG",
                "CREATE PROPERTY Entity.entityId IF NOT EXISTS STRING",
                "CREATE PROPERTY Entity.communityId IF NOT EXISTS STRING",
                "CREATE PROPERTY Community.kbId IF NOT EXISTS LONG",
                "CREATE PROPERTY Community.graphVersion IF NOT EXISTS LONG",
                "CREATE PROPERTY Community.communityId IF NOT EXISTS STRING",
                "CREATE PROPERTY Chunk.kbId IF NOT EXISTS LONG",
                "CREATE PROPERTY Chunk.graphVersion IF NOT EXISTS LONG",
                "CREATE PROPERTY Chunk.sourceChunkId IF NOT EXISTS STRING",
                "CREATE PROPERTY RelationEvidence.kbId IF NOT EXISTS LONG",
                "CREATE PROPERTY RelationEvidence.graphVersion IF NOT EXISTS LONG",
                "CREATE PROPERTY RelationEvidence.relationId IF NOT EXISTS STRING",
                "CREATE PROPERTY RelationEvidence.evidenceId IF NOT EXISTS STRING",
                "CREATE PROPERTY RelationEvidence.sourceEntityId IF NOT EXISTS STRING");
    }

    static List<String> indexes() {
        return List.of(
                "CREATE INDEX IF NOT EXISTS ON SchemaVersion (name) UNIQUE",
                "CREATE INDEX IF NOT EXISTS ON Entity (kbId, graphVersion, entityId) UNIQUE",
                "CREATE INDEX IF NOT EXISTS ON Entity (kbId, graphVersion, communityId) NOTUNIQUE",
                "CREATE INDEX IF NOT EXISTS ON Community (kbId, graphVersion, communityId) UNIQUE",
                "CREATE INDEX IF NOT EXISTS ON Chunk (kbId, graphVersion, sourceChunkId) UNIQUE",
                "CREATE INDEX IF NOT EXISTS ON RelationEvidence (kbId, graphVersion, evidenceId) UNIQUE",
                "CREATE INDEX IF NOT EXISTS ON RelationEvidence (kbId, graphVersion, sourceEntityId) NOTUNIQUE");
    }

    static String schemaVersionUpsert() {
        return "UPDATE SchemaVersion SET name = :name, version = :version "
                + "UPSERT WHERE name = :name";
    }
}
