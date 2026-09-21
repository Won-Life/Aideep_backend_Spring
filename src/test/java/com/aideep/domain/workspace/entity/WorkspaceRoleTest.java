package com.aideep.domain.workspace.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WorkspaceRoleTest {

    @Test
    void ownerCanUseEveryPermission() {
        assertThat(WorkspacePermission.values()).allMatch(WorkspaceRole.OWNER::allows);
    }

    @Test
    void editorCanViewAndEditOnly() {
        assertThat(WorkspaceRole.EDITOR.allows(WorkspacePermission.VIEW)).isTrue();
        assertThat(WorkspaceRole.EDITOR.allows(WorkspacePermission.EDIT)).isTrue();
        assertThat(WorkspaceRole.EDITOR.allows(WorkspacePermission.MANAGE_MEMBERS)).isFalse();
        assertThat(WorkspaceRole.EDITOR.allows(WorkspacePermission.DELETE)).isFalse();
    }

    @Test
    void viewerCanViewOnly() {
        assertThat(WorkspaceRole.VIEWER.allows(WorkspacePermission.VIEW)).isTrue();
        assertThat(WorkspaceRole.VIEWER.allows(WorkspacePermission.EDIT)).isFalse();
    }
}
