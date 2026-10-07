package com.aideep.domain.node.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EdgeTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SOURCE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID TARGET_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Test
    void createsEdgeWithVersionOneAndCommonTimestamps() {
        Edge edge = Edge.create(WORKSPACE_ID, SOURCE_ID, TARGET_ID, "right", "left", NOW);

        assertThat(edge.getId()).isNotNull();
        assertThat(edge.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(edge.getSourceId()).isEqualTo(SOURCE_ID);
        assertThat(edge.getTargetId()).isEqualTo(TARGET_ID);
        assertThat(edge.getSourceHandle()).isEqualTo("right");
        assertThat(edge.getTargetHandle()).isEqualTo("left");
        assertThat(edge.getVersion()).isEqualTo(1);
        assertThat(edge.getCreatedAt()).isEqualTo(NOW);
        assertThat(edge.getUpdatedAt()).isEqualTo(NOW);
        assertThat(edge.getDeletedAt()).isNull();
    }

    @Test
    void allowsNullHandles() {
        Edge edge = Edge.create(WORKSPACE_ID, SOURCE_ID, TARGET_ID, null, null, NOW);

        assertThat(edge.getSourceHandle()).isNull();
        assertThat(edge.getTargetHandle()).isNull();
    }

    @Test
    void marksDeletedAndUpdatesTimestamp() {
        Edge edge = Edge.create(WORKSPACE_ID, SOURCE_ID, TARGET_ID, null, null, NOW);

        edge.delete(NOW.plusSeconds(60));

        assertThat(edge.getDeletedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(edge.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(edge.getCreatedAt()).isEqualTo(NOW);
    }
}
