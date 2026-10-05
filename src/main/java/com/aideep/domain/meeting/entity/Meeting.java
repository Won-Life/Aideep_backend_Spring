package com.aideep.domain.meeting.entity;

import com.aideep.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * 봇을 초대한 회의 한 건. Recall 웹훅으로 받은 상태 변화를 반영해 시작/종료 시각을 관리한다.
 */
@Entity
@Table(name = "meetings")
@AttributeOverride(name = "id", column = @Column(name = "meeting_id"))
@Getter
public class Meeting extends BaseEntity {

    public static final int MAX_URL_LENGTH = 2048;

    @Column(name = "workspace_id", nullable = false)
    private UUID workspaceId;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "bot_id", unique = true)
    private UUID botId;

    @Column(name = "meeting_url", nullable = false, length = MAX_URL_LENGTH)
    private String meetingUrl;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(name = "bot_type", nullable = false, columnDefinition = "bot_type_enum")
    private Bottype botType;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false, columnDefinition = "meeting_status_enum")
    private MeetingStatus status;

    @Column(name = "status_sub_code", length = 100)
    private String statusSubCode;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /**
     * 마지막으로 반영한 Recall 상태 이벤트의 발생 시각. 순서가 뒤바뀐 웹훅을 무시하는 기준이다.
     */
    @Column(name = "last_event_at")
    private Instant lastEventAt;

    protected Meeting() {
    }

    private Meeting(UUID workspaceId, UUID nodeId, UUID userId, String meetingUrl, Bottype botType, Instant now) {
        super(now);
        this.workspaceId = workspaceId;
        this.nodeId = nodeId;
        this.userId = userId;
        this.meetingUrl = meetingUrl;
        this.botType = botType;
        status = MeetingStatus.REQUESTED;
    }

    public static Meeting request(UUID workspaceId, UUID nodeId, UUID userId, String meetingUrl, Bottype botType,
                                  Instant now) {
        return new Meeting(workspaceId, nodeId, userId, meetingUrl, botType, now);
    }

    public void linkBot(UUID botId, Instant now) {
        this.botId = botId;
        updateTimestamp(now);
    }

    /**
     * Recall 상태 이벤트를 반영한다. 이벤트 발생 시각이 이미 반영한 것보다 과거면 무시하고 false를 반환한다.
     */
    public boolean applyStatus(MeetingStatus status, String statusSubCode, Instant occurredAt, Instant now) {
        if (lastEventAt != null && occurredAt.isBefore(lastEventAt)) {
            return false;
        }
        this.status = status;
        this.statusSubCode = statusSubCode;
        lastEventAt = occurredAt;
        if (status == MeetingStatus.RECORDING && startedAt == null) {
            startedAt = occurredAt;
        }
        if (status.isTerminal() && endedAt == null) {
            endedAt = occurredAt;
        }
        updateTimestamp(now);
        return true;
    }
}
