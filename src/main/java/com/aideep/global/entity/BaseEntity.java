package com.aideep.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

@Getter
@MappedSuperclass
public abstract class BaseEntity {
    @Id
    private UUID id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected BaseEntity() {
    }

    protected BaseEntity(Instant now) {
        this(UUID.randomUUID(), now);
    }

    protected BaseEntity(UUID id, Instant now) {
        this.id = id;
        createdAt = now;
        updatedAt = now;
    }

    protected void updateTimestamp(Instant now) {
        updatedAt = now;
    }

    protected void markDeleted(Instant now) {
        deletedAt = now;
        updateTimestamp(now);
    }

    protected void restore(Instant now) {
        deletedAt = null;
        updateTimestamp(now);
    }
}
