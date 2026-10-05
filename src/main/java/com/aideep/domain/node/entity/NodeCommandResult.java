package com.aideep.domain.node.entity;

import com.aideep.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;

@Entity
@Table(name = "node_command_results")
@Getter
public class NodeCommandResult extends BaseEntity {
    @Column(name = "command_event_id", nullable = false, unique = true)
    private UUID commandEventId;

    @Column(nullable = false, columnDefinition = "text")
    private String data;

    @Column(name = "published_at")
    private Instant publishedAt;

    public void requestPublication(Instant now) {
        publishedAt = null;
        updateTimestamp(now);
    }

    public void markPublished(Instant now) {
        publishedAt = now;
        updateTimestamp(now);
    }

    protected NodeCommandResult() {
    }

    public NodeCommandResult(UUID eventId, UUID commandEventId, String data, Instant now) {
        super(eventId, now);
        this.commandEventId = commandEventId;
        this.data = data;
    }
}
