package com.aideep.domain.workspace.entity;

import java.util.EnumSet;
import java.util.Set;

public enum WorkspaceRole {
    OWNER(EnumSet.allOf(WorkspacePermission.class)),
    EDITOR(EnumSet.of(WorkspacePermission.VIEW, WorkspacePermission.EDIT)),
    VIEWER(EnumSet.of(WorkspacePermission.VIEW));

    private final Set<WorkspacePermission> permissions;

    WorkspaceRole(Set<WorkspacePermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean allows(WorkspacePermission workspacePermission) {
        return permissions.contains(workspacePermission);
    }
}
