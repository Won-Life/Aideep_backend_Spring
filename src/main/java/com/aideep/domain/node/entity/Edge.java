package com.aideep.domain.node.entity;

import com.aideep.global.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * 노드 사이의 연결. {@code source} → {@code target} 방향이며 하위 노드 탐색의 기준이 된다.
 */
@Entity
@Table(name = "edges")
@AttributeOverride(name = "id", column = @Column(name = "edge_id"))
@Getter
public class Edge extends BaseEntity {

    public static final int MAX_HANDLE_LENGTH = 250;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "source_id", nullable = false)
    private UUID sourceId;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(name = "source_handle", length = MAX_HANDLE_LENGTH)
    private String sourceHandle;

    @Column(name = "target_handle", length = MAX_HANDLE_LENGTH)
    private String targetHandle;

    @Column(nullable = false)
    private int version;

    protected Edge() {
    }

    private Edge(UUID workspaceId, UUID sourceId, UUID targetId, String sourceHandle, String targetHandle,
                 Instant now) {
        super(now);
        this.workspaceId = workspaceId;
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.sourceHandle = sourceHandle;
        this.targetHandle = targetHandle;
        version = 1;
    }

    public static Edge create(UUID workspaceId, UUID sourceId, UUID targetId, String sourceHandle,
                              String targetHandle, Instant now) {
        return new Edge(workspaceId, sourceId, targetId, sourceHandle, targetHandle, now);
    }

    public void delete(Instant now) {
        markDeleted(now);
    }
}
