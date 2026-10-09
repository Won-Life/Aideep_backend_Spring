package com.aideep.domain.meeting.dto.event;

import java.util.UUID;

/**
 * 커밋 후 실시간 발행을 트리거하는 애플리케이션 이벤트. {@code eventId}는 로그 상관용이며 WS payload에는 포함하지 않는다.
 */
public record MeetingRealtimeEvent(UUID eventId, MeetingWorkspaceEvent payload) {

    public static MeetingRealtimeEvent of(MeetingWorkspaceEvent payload) {
        return new MeetingRealtimeEvent(UUID.randomUUID(), payload);
    }
}
