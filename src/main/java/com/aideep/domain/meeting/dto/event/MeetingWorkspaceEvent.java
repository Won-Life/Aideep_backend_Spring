package com.aideep.domain.meeting.dto.event;

import com.aideep.domain.meeting.entity.Meeting;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.UUID;

/**
 * {@code aideep-ws}가 워크스페이스 room으로 전달하는 회의 실시간 payload.
 * <p>
 * WS 서버는 DB를 조회하지 않으므로 payload는 자족적이어야 한다. 라우팅 기준인 {@code workspaceId}는 최상위에 두고, 전송 지시
 * ({@code targetRoom} 등)는 넣지 않는다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeetingWorkspaceEvent(
        String type,
        UUID workspaceId,
        UUID userId,
        UUID meetingId,
        UUID nodeId,
        UUID botId,
        String status,
        Instant occurredAt,

        /** 실패 이벤트에만 담는다. Recall {@code status.sub_code} 원문이며 FE 에러 메시지 분기용이다. */
        String statusSubCode
) {

    public static final String BOT_REQUESTED = "MEETING_BOT_REQUESTED";
    public static final String BOT_JOINED = "MEETING_BOT_JOINED";
    public static final String BOT_LEFT = "MEETING_BOT_LEFT";
    public static final String BOT_FAILED = "MEETING_BOT_FAILED";

    public static MeetingWorkspaceEvent botRequested(Meeting meeting, Instant occurredAt) {
        return of(BOT_REQUESTED, meeting, occurredAt, null);
    }

    public static MeetingWorkspaceEvent botJoined(Meeting meeting, Instant occurredAt) {
        return of(BOT_JOINED, meeting, occurredAt, null);
    }

    public static MeetingWorkspaceEvent botLeft(Meeting meeting, Instant occurredAt) {
        return of(BOT_LEFT, meeting, occurredAt, null);
    }

    public static MeetingWorkspaceEvent botFailed(Meeting meeting, Instant occurredAt) {
        return of(BOT_FAILED, meeting, occurredAt, meeting.getStatusSubCode());
    }

    private static MeetingWorkspaceEvent of(String type, Meeting meeting, Instant occurredAt, String statusSubCode) {
        return new MeetingWorkspaceEvent(type, meeting.getWorkspaceId(), meeting.getUserId(), meeting.getId(),
                meeting.getNodeId(), meeting.getBotId(), meeting.getStatus().name(), occurredAt, statusSubCode);
    }
}
