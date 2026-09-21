package com.aideep.domain.workspace.entity;

import com.aideep.global.entity.BaseEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;

@Entity
@Table(name = "workspaces")
@AttributeOverride(name = "id", column = @Column(name = "workspace_id"))
@Getter
public class Workspace extends BaseEntity {

    public static final String DEFAULT_TITLE = "Untitled";

    @Column(nullable = false, length = 255)
    private String title;

    protected Workspace() {
    }

    public Workspace(String title, Instant now) {
        super(now);
        this.title = title;
    }

    public static Workspace untitled(Instant now) {
        return new Workspace(DEFAULT_TITLE, now);
    }

    public void rename(String title, Instant now) {
        this.title = title;
        updateTimestamp(now);
    }

    public void delete(Instant now) {
        markDeleted(now);
    }
}
