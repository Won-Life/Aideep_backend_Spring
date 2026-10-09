package com.aideep.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.aideep.domain.meeting.dto.event.MeetingRealtimeEvent;
import com.aideep.domain.meeting.dto.event.MeetingWorkspaceEvent;
import com.aideep.domain.meeting.entity.Bottype;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.global.event.RealtimeEventEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

class MeetingRealtimeEventPublisherTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID NODE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID USER_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID BOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant CREATED_AT = Instant.parse("2026-10-06T01:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-06T01:10:00Z");

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    private StringRedisTemplate stringRedisTemplate;
    private MeetingRealtimeEventPublisher meetingRealtimeEventPublisher;

    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        meetingRealtimeEventPublisher = new MeetingRealtimeEventPublisher(stringRedisTemplate,
                JsonMapper.builder().build(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void publishesTheWorkspaceEventEnvelopeToTheSharedRealtimeChannel() {
        Meeting meeting = meeting();

        meetingRealtimeEventPublisher.publish(
                MeetingRealtimeEvent.of(MeetingWorkspaceEvent.botJoined(meeting, CREATED_AT.plusSeconds(10))));

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).convertAndSend(eq(RealtimeEventEnvelope.CHANNEL), messageCaptor.capture());
        JsonNode root = jsonMapper.readTree(messageCaptor.getValue());
        assertThat(root.path("v").asInt()).isEqualTo(1);
        assertThat(root.path("kind").asString()).isEqualTo("WORKSPACE_EVENT");
        assertThat(root.path("origin").asString()).isEqualTo("spring-api");
        assertThat(root.path("publishedAt").asString()).isEqualTo(NOW.toString());
        assertThat(root.path("messageId").asString()).isNotBlank();

        JsonNode payload = root.path("payload");
        assertThat(payload.path("type").asString()).isEqualTo("MEETING_BOT_JOINED");
        assertThat(payload.path("workspaceId").asString()).isEqualTo(WORKSPACE_ID.toString());
        assertThat(payload.path("userId").asString()).isEqualTo(USER_ID.toString());
        assertThat(payload.path("nodeId").asString()).isEqualTo(NODE_ID.toString());
        assertThat(payload.path("botId").asString()).isEqualTo(BOT_ID.toString());
        assertThat(payload.path("meetingId").asString()).isEqualTo(meeting.getId().toString());
        assertThat(payload.path("occurredAt").asString()).isEqualTo(CREATED_AT.plusSeconds(10).toString());
        // 라우팅은 WS 서버의 모델이다. 전송 지시를 넣지 않는다.
        assertThat(payload.has("targetRoom")).isFalse();
        assertThat(payload.has("socketId")).isFalse();
        // 실패 이벤트가 아니면 키 자체를 보내지 않는다.
        assertThat(payload.has("statusSubCode")).isFalse();
    }

    @Test
    void includesStatusSubCodeOnFailureEvents() {
        Meeting meeting = meeting();
        meeting.applyStatus(MeetingStatus.FAILED, "meeting_not_found",
                CREATED_AT.plusSeconds(5), NOW);

        meetingRealtimeEventPublisher.publish(
                MeetingRealtimeEvent.of(MeetingWorkspaceEvent.botFailed(meeting, CREATED_AT.plusSeconds(5))));

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).convertAndSend(eq(RealtimeEventEnvelope.CHANNEL), messageCaptor.capture());
        JsonNode payload = jsonMapper.readTree(messageCaptor.getValue()).path("payload");
        assertThat(payload.path("type").asString()).isEqualTo("MEETING_BOT_FAILED");
        assertThat(payload.path("statusSubCode").asString()).isEqualTo("meeting_not_found");
    }

    /**
     * best-effort다. 발행 실패가 이미 커밋된 상태나 웹훅 200 응답을 되돌리면 안 된다.
     */
    @Test
    void swallowsRedisFailuresSoCommittedStateStands() {
        doThrow(new RedisConnectionFailureException("down"))
                .when(stringRedisTemplate).convertAndSend(anyString(), anyString());

        assertThatCode(() -> meetingRealtimeEventPublisher.publish(
                MeetingRealtimeEvent.of(MeetingWorkspaceEvent.botLeft(meeting(), CREATED_AT.plusSeconds(20)))))
                .doesNotThrowAnyException();
    }

    private Meeting meeting() {
        Meeting meeting = Meeting.request(
                WORKSPACE_ID, NODE_ID, USER_ID, "https://meet.google.com/abc-defg-hij", Bottype.GOOGLE, CREATED_AT);
        meeting.linkBot(BOT_ID, CREATED_AT);
        return meeting;
    }
}
