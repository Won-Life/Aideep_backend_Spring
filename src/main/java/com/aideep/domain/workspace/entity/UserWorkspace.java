package com.aideep.domain.workspace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users_workspaces")
@Getter
public class UserWorkspace {

    @EmbeddedId
    private UserWorkspaceId id;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "workspace_role_enum")
    private WorkspaceRole role;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected UserWorkspace() {
    }

    public UserWorkspace(UUID userId, UUID workspaceId, WorkspaceRole role, Instant joinedAt) {
        id = new UserWorkspaceId(userId, workspaceId);
        this.role = role;
        this.joinedAt = joinedAt;
    }

    public void changeRole(WorkspaceRole role) {
        this.role = role;
    }

    public void remove(Instant now) {
        deletedAt = now;
    }

    public void restore(WorkspaceRole role) {
        this.role = role;
        deletedAt = null;
    }

    public boolean isActive() {
        return deletedAt == null;
    }
}
