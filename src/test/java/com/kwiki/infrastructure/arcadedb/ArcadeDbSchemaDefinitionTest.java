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
                .contains("CREATE INDEX Entity[kbId, graphVersion, entityId] UNIQUE")
                .contains("CREATE INDEX RelationEvidence[kbId, graphVersion, relationId, evidenceId] UNIQUE");
        assertThat(ArcadeDbSchemaDefinition.schemaVersionUpsert()).contains("UPSERT")
                .contains(":version");
    }
}
