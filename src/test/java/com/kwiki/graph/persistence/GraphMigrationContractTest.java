package com.kwiki.graph.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class GraphMigrationContractTest {

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
