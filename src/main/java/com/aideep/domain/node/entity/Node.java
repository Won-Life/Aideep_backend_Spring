package com.aideep.domain.node.entity;

import com.aideep.domain.node.exception.NodeError;
import com.aideep.global.entity.BaseEntity;
import com.aideep.global.exception.BusinessException;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "nodes")
@AttributeOverride(name = "id", column = @Column(name = "node_id"))
@Getter
public class Node extends BaseEntity {

    public static final int MAX_TITLE_LENGTH = 500;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(length = MAX_TITLE_LENGTH)
    private String title;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "node_type", nullable = false, columnDefinition = "node_type_enum")
    private NodeType nodeType;

    @Column(name = "position_x")
    private Double positionX;

    @Column(name = "position_y")
    private Double positionY;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String content;

    @Column(nullable = false)
    private int version;

    private Integer depth;

    protected Node() {
    }

    private Node(UUID workspaceId, String title, NodeType nodeType, double positionX, double positionY,
                 String content, Instant now) {
        super(now);
        requireTitleLength(title);
        requireFinitePosition(positionX, positionY);
        this.workspaceId = workspaceId;
        this.title = title;
        this.nodeType = nodeType;
        this.positionX = positionX;
        this.positionY = positionY;
        this.content = content;
        version = 1;
        depth = 0;
    }

    public static Node create(UUID workspaceId, String title, NodeType nodeType, double positionX, double positionY,
                              String content, Instant now) {
        return new Node(workspaceId, title, nodeType, positionX, positionY, content, now);
    }

    public void applyPatch(String title, NodeType nodeType, String content, int expectedVersion, Instant now) {
        if (version != expectedVersion) {
            throw new BusinessException(NodeError.STALE_NODE_VERSION,
                    Map.of("nodeId", getId(), "expectedVersion", expectedVersion, "currentVersion", version));
        }
        if (title != null) {
            requireTitleLength(title);
            this.title = title;
        }
        if (nodeType != null) {
            this.nodeType = nodeType;
        }
        if (content != null) {
            this.content = content;
        }
        version = version + 1;
        updateTimestamp(now);
    }

    public void delete(Instant now) {
        markDeleted(now);
    }

    private static void requireTitleLength(String title) {
        if (title != null && title.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(NodeError.INVALID_PAYLOAD,
                    "title must not exceed " + MAX_TITLE_LENGTH + " characters");
        }
    }

    private static void requireFinitePosition(double positionX, double positionY) {
        if (!Double.isFinite(positionX) || !Double.isFinite(positionY)) {
            throw new BusinessException(NodeError.INVALID_PAYLOAD, "position must be finite");
        }
    }
}
