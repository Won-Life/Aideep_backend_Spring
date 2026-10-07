package com.aideep.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aideep.domain.meeting.config.MeetingEventProperties;
import com.aideep.domain.meeting.dto.event.MeetingStartedEvent;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MeetingEventPublisherTest {

    private static final UUID EVENT_ID = UUID.fromString("9f1d0000-0000-4000-8000-000000000000");
    private static final UUID MEETING_ID = UUID.fromString("a1b20000-0000-4000-8000-000000000000");
    private static final UUID NODE_ID = UUID.fromString("c3d40000-0000-4000-8000-000000000000");
    private static final UUID WORKSPACE_ID = UUID.fromString("e5f60000-0000-4000-8000-000000000000");
    private static final UUID BOT_ID = UUID.fromString("07190000-0000-4000-8000-000000000000");
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-06T01:02:03Z");
    private static final String STREAM = "onnode:ai:meeting-events:v1";

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @Test
    void publishesEnvelopeAgreedWithTheAiServer() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        MeetingEventPublisher meetingEventPublisher = publisher(stringRedisTemplate, true);

        meetingEventPublisher.publish(event());

        ArgumentCaptor<Map<Object, Object>> entryCaptor = ArgumentCaptor.forClass(Map.class);
        org.mockito.Mockito.verify(streamOperations).add(org.mockito.ArgumentMatchers.eq(STREAM),
                entryCaptor.capture());
        JsonNode published = jsonMapper.readTree(String.valueOf(entryCaptor.getValue().get("data")));
        assertThat(published.path("version").asInt()).isEqualTo(1);
        assertThat(published.path("type").asString()).isEqualTo("MEETING_STARTED");
        assertThat(published.path("eventId").asString()).isEqualTo(EVENT_ID.toString());
        assertThat(published.path("occurredAt").asString()).isEqualTo("2026-10-06T01:02:03Z");
        assertThat(published.path("source").asString()).isEqualTo("spring-api");
        assertThat(published.path("payload").path("meetingId").asString()).isEqualTo(MEETING_ID.toString());
        assertThat(published.path("payload").path("nodeId").asString()).isEqualTo(NODE_ID.toString());
        assertThat(published.path("payload").path("workspaceId").asString()).isEqualTo(WORKSPACE_ID.toString());
        assertThat(published.path("payload").path("botId").asString()).isEqualTo(BOT_ID.toString());
        // 노드 명령 계약과 달리 최상위에 workspaceId와 eventType을 두지 않는다.
        assertThat(published.has("workspaceId")).isFalse();
        assertThat(published.has("eventType")).isFalse();
    }

    @Test
    void swallowsRedisFailureSoTheCommittedStatusIsNotUndone() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        StreamOperations<String, Object, Object> streamOperations = mock(StreamOperations.class);
        when(stringRedisTemplate.opsForStream()).thenReturn(streamOperations);
        doThrow(new org.springframework.dao.QueryTimeoutException("redis down"))
                .when(streamOperations).add(anyString(), any(Map.class));
        MeetingEventPublisher meetingEventPublisher = publisher(stringRedisTemplate, true);

        assertThatCode(() -> meetingEventPublisher.publish(event())).doesNotThrowAnyException();
    }

    @Test
    void doesNotTouchRedisWhenPublicationIsDisabled() {
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        MeetingEventPublisher meetingEventPublisher = publisher(stringRedisTemplate, false);

        meetingEventPublisher.publish(event());

        verifyNoInteractions(stringRedisTemplate);
    }

    private MeetingEventPublisher publisher(StringRedisTemplate stringRedisTemplate, boolean enabled) {
        return new MeetingEventPublisher(stringRedisTemplate, new MeetingEventProperties(enabled, STREAM),
                jsonMapper);
    }

    private MeetingStartedEvent event() {
        return MeetingStartedEvent.of(EVENT_ID, OCCURRED_AT, MEETING_ID, NODE_ID, WORKSPACE_ID, BOT_ID);
    }
}
