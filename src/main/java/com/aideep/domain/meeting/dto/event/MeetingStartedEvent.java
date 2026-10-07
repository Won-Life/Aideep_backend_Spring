package com.aideep.domain.meeting.dto.event;

import java.time.Instant;
import java.util.UUID;

/**
 * 회의 녹음이 시작됐음을 AI 서버에 알리는 이벤트. envelope 키 이름({@code type}, {@code source})은 AI 서버와 공유한 계약이며 노드 명령
 * 계약({@code eventType}, 최상위 {@code workspaceId})과 다르다.
 */
public record MeetingStartedEvent(
        int version,
        String type,
        UUID eventId,
        Instant occurredAt,
        String source,
        Payload payload
) {

    public static final int VERSION = 1;
    public static final String TYPE = "MEETING_STARTED";
    public static final String SOURCE = "spring-api";

    /**
     * {@code occurredAt}은 발행 시각이 아니라 녹음이 시작된 시각(웹훅의 상태 발생 시각)이다.
     */
    public static MeetingStartedEvent of(UUID eventId, Instant occurredAt, UUID meetingId, UUID nodeId,
                                         UUID workspaceId, UUID botId) {
        return new MeetingStartedEvent(VERSION, TYPE, eventId, occurredAt, SOURCE,
                new Payload(meetingId, nodeId, workspaceId, botId));
    }

    public record Payload(UUID meetingId, UUID nodeId, UUID workspaceId, UUID botId) {
    }
}
