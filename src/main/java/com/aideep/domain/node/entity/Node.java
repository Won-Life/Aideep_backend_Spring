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

    /**
     * 진행 중인 회의가 이 노드를 잠갔다면 그 회의 ID다. null이면 사용자 수정이 가능하다.
     * AI 서버가 보내는 노드 명령은 이 값과 무관하게 반영한다.
     */
    @Column(name = "locked_by_meeting_id")
    private UUID lockedByMeetingId;

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

    public boolean isLocked() {
        return lockedByMeetingId != null;
    }

    /**
     * 회의가 이 노드의 사용자 수정을 잠근다. 이미 다른 회의가 잠근 노드는 그대로 두고 false를 반환한다.
     * <p>
     * 먼저 잠근 회의의 소유를 유지하는 이유는, 나중 회의가 덮어쓰면 먼저 끝난 회의가 아직 진행 중인 회의의 잠금을 풀어버리기 때문이다.
     */
    public boolean lockForMeeting(UUID meetingId, Instant now) {
        if (lockedByMeetingId != null) {
            return false;
        }
        lockedByMeetingId = meetingId;
        updateTimestamp(now);
        return true;
    }

    /**
     * 잠근 회의만 잠금을 해제할 수 있다. 다른 회의가 잠근 노드면 그대로 두고 false를 반환한다.
     */
    public boolean unlockForMeeting(UUID meetingId, Instant now) {
        if (lockedByMeetingId == null || !lockedByMeetingId.equals(meetingId)) {
            return false;
        }
        lockedByMeetingId = null;
        updateTimestamp(now);
        return true;
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
