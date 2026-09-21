package com.aideep.domain.workspace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@EqualsAndHashCode
public class UserWorkspaceId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    protected UserWorkspaceId() {
    }

    public UserWorkspaceId(UUID userId, UUID workspaceId) {
        this.userId = userId;
        this.workspaceId = workspaceId;
    }
}
