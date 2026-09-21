package com.aideep.domain.workspace.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class WorkspaceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-21T01:00:00Z");

    @Test
    void createsUntitledWorkspaceWithCommonFields() {
        Workspace workspace = Workspace.untitled(CREATED_AT);

        assertThat(workspace.getId()).isNotNull();
        assertThat(workspace.getTitle()).isEqualTo(Workspace.DEFAULT_TITLE);
        assertThat(workspace.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(workspace.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(workspace.getDeletedAt()).isNull();
    }

    @Test
    void renamesWorkspaceAndUpdatesTimestamp() {
        Workspace workspace = Workspace.untitled(CREATED_AT);

        workspace.rename("AIDEEP 팀", UPDATED_AT);

        assertThat(workspace.getTitle()).isEqualTo("AIDEEP 팀");
        assertThat(workspace.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }

    @Test
    void softDeletesWorkspace() {
        Workspace workspace = Workspace.untitled(CREATED_AT);

        workspace.delete(UPDATED_AT);

        assertThat(workspace.getDeletedAt()).isEqualTo(UPDATED_AT);
        assertThat(workspace.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }
}
