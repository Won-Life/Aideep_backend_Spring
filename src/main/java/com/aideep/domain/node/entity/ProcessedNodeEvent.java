package com.aideep.domain.node.entity;

import com.aideep.global.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "processed_node_events")
@AttributeOverride(name = "id", column = @Column(name = "event_id"))
@Getter
public class ProcessedNodeEvent extends BaseEntity {

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedNodeEvent() {
    }

    public ProcessedNodeEvent(UUID eventId, String eventType, UUID workspaceId, Instant occurredAt, Instant now) {
        super(eventId, now);
        this.eventType = eventType;
        this.workspaceId = workspaceId;
        this.occurredAt = occurredAt;
        processedAt = now;
    }
}
