package com.aideep.domain.meeting.dto.response;

import com.aideep.domain.meeting.entity.Meeting;

import java.time.Instant;
import java.util.UUID;

/**
 * 진행 중 회의 한 건. Pub/Sub 실시간 이벤트를 놓친 클라이언트가 재접속 시 상태를 되맞추는 용도다.
 */
public record ActiveMeetingResponse(
        UUID meetingId,
        UUID nodeId,
        UUID botId,
        String status,
        String statusSubCode,
        Instant startedAt,
        Instant createdAt
) {

    public static ActiveMeetingResponse from(Meeting meeting) {
        return new ActiveMeetingResponse(meeting.getId(), meeting.getNodeId(), meeting.getBotId(),
                meeting.getStatus().name(), meeting.getStatusSubCode(), meeting.getStartedAt(),
                meeting.getCreatedAt());
    }
}
