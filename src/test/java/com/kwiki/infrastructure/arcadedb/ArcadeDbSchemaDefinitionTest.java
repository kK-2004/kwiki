package com.kwiki.infrastructure.arcadedb;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArcadeDbSchemaDefinitionTest {

    @Test
    void schemaIsReentrantAndContainsOnlyTheDeclaredGraphTypes() {
        assertThat(ArcadeDbSchemaDefinition.types())
                .contains("CREATE VERTEX TYPE Entity IF NOT EXISTS")
                .contains("CREATE EDGE TYPE CONNECTED IF NOT EXISTS")
                .doesNotContain("Document -> Chunk");
        assertThat(ArcadeDbSchemaDefinition.indexes())
                .contains("CREATE INDEX IF NOT EXISTS ON Entity (kbId, graphVersion, entityId) UNIQUE")
                .contains("CREATE INDEX IF NOT EXISTS ON RelationEvidence (kbId, graphVersion, evidenceId) UNIQUE")
                .allMatch(statement -> statement.startsWith("CREATE INDEX IF NOT EXISTS ON "));
        // ArcadeDB 建索引前要求属性已定义：每个索引字段都有对应的 CREATE PROPERTY
        for (String index : ArcadeDbSchemaDefinition.indexes()) {
            String type = index.replaceAll(".* ON (\\w+) \\(.*", "$1");
            for (String field : index.replaceAll(".*\\((.*)\\).*", "$1").split(",\\s*")) {
                assertThat(ArcadeDbSchemaDefinition.properties())
                        .anyMatch(p -> p.startsWith("CREATE PROPERTY " + type + "." + field + " IF NOT EXISTS"));
            }
        }
        assertThat(ArcadeDbSchemaDefinition.schemaVersionUpsert()).contains("UPSERT")
                .contains(":version");
    }
}
