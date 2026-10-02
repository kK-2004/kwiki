package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GraphMigrationContractTest {

    @Test
    void 字段格式迁移回填后约束非空_旧列仅在Chunk身份表删除() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V37__index_schema_plugin_ids.sql"));
        for (String table : java.util.List.of("search_index_version", "graph_build_run", "graph_snapshot")) {
            String column = table.equals("search_index_version") ? "index_schema" : "chunk_index_schema";
            assertThat(migration).contains("ALTER TABLE " + table + " ADD COLUMN " + column + " VARCHAR(64) NULL")
                    .contains("UPDATE " + table).contains("WHEN 3 THEN 'kwiki-schema-1'")
                    .contains("CONCAT('legacy-schema-', mapping_schema_version)")
                    .contains("ALTER TABLE " + table + " MODIFY COLUMN " + column + " VARCHAR(64) NOT NULL")
                    .contains("ALTER TABLE " + table + " DROP COLUMN mapping_schema_version;");
        }
        assertThat(migration).doesNotContain("community_index_version");
    }

    @Test
    void graphMigrationsKeepIdentityIdempotencyAndPublicationConstraints() throws Exception {
        String extraction = Files.readString(Path.of(
                "src/main/resources/db/migration/V28__graph_extraction_metadata.sql"));
        String snapshots = Files.readString(Path.of(
                "src/main/resources/db/migration/V29__graph_build_snapshots_and_publication.sql"));
        String readLeases = Files.readString(Path.of(
                "src/main/resources/db/migration/V30__graph_snapshot_read_leases.sql"));
        String scheduleTrigger = Files.readString(Path.of(
                "src/main/resources/db/migration/V31__graph_schedule_trigger.sql"));

        assertThat(extraction)
                .contains("uk_graph_extraction_identity")
                .contains("uk_graph_extraction_target_idempotency")
                .contains("content_epoch")
                .contains("security_epoch");
        assertThat(snapshots)
                .contains("uk_graph_build_batch_idempotency")
                .contains("uk_graph_build_run_active_kb")
                .contains("uk_graph_publication_pair")
                .contains("chunk_index_version")
                .contains("community_index_version")
                .contains("graph_version")
                .contains("mapping_schema_version")
                .contains("state IN ('BUILDING', 'READY', 'PUBLISHED'");
        assertThat(readLeases)
                .contains("graph_snapshot_read_lease")
                .contains("REFERENCES graph_snapshot (id)")
                .contains("expires_at");
        assertThat(scheduleTrigger)
                .contains("PRIMARY KEY (schedule_date)")
                .contains("'TRIGGERED', 'SKIPPED_ACTIVE', 'CATCH_UP'")
                .contains("linked_batch_id");
    }
}
