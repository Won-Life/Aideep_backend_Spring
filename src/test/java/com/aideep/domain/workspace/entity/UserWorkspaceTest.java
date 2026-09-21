package com.aideep.domain.workspace.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class UserWorkspaceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant JOINED_AT = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant REMOVED_AT = Instant.parse("2026-09-21T01:00:00Z");

    @Test
    void createsMembershipWithCompositeId() {
        UserWorkspace userWorkspace = new UserWorkspace(USER_ID, WORKSPACE_ID, WorkspaceRole.EDITOR, JOINED_AT);

        assertThat(userWorkspace.getId().getUserId()).isEqualTo(USER_ID);
        assertThat(userWorkspace.getId().getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(userWorkspace.getRole()).isEqualTo(WorkspaceRole.EDITOR);
        assertThat(userWorkspace.getJoinedAt()).isEqualTo(JOINED_AT);
        assertThat(userWorkspace.isActive()).isTrue();
    }

    @Test
    void removesAndRestoresMembership() {
        UserWorkspace userWorkspace = new UserWorkspace(USER_ID, WORKSPACE_ID, WorkspaceRole.VIEWER, JOINED_AT);

        userWorkspace.remove(REMOVED_AT);

        assertThat(userWorkspace.isActive()).isFalse();
        assertThat(userWorkspace.getDeletedAt()).isEqualTo(REMOVED_AT);

        userWorkspace.restore(WorkspaceRole.EDITOR);

        assertThat(userWorkspace.isActive()).isTrue();
        assertThat(userWorkspace.getRole()).isEqualTo(WorkspaceRole.EDITOR);
    }
}
