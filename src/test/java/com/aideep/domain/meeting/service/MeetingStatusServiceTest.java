package com.aideep.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aideep.domain.meeting.dto.event.MeetingRealtimeEvent;
import com.aideep.domain.meeting.dto.event.MeetingStartedEvent;
import com.aideep.domain.meeting.dto.event.MeetingWorkspaceEvent;
import com.aideep.domain.meeting.entity.Bottype;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.repository.MeetingRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class MeetingStatusServiceTest {

    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID NODE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID USER_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID BOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant CREATED_AT = Instant.parse("2026-10-06T01:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-06T01:10:00Z");

    private MeetingRepository meetingRepository;
    private ApplicationEventPublisher applicationEventPublisher;
    private MeetingStatusService meetingStatusService;

    @BeforeEach
    void setUp() {
        meetingRepository = mock(MeetingRepository.class);
        applicationEventPublisher = mock(ApplicationEventPublisher.class);
        meetingStatusService = new MeetingStatusService(meetingRepository, applicationEventPublisher,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void reportsUnknownBotWithoutTouchingAnything() {
        when(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID)).thenReturn(Optional.empty());

        MeetingStatusService.Result result = meetingStatusService.apply(
                BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));

        assertThat(result).isEqualTo(MeetingStatusService.Result.UNKNOWN_BOT);
    }

    @Test
    void reportsRecordingStartedOnlyOnTheFirstTransition() {
        Meeting meeting = meeting();
        when(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID)).thenReturn(Optional.of(meeting));

        MeetingStatusService.Result first = meetingStatusService.apply(
                BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));
        MeetingStatusService.Result redelivered = meetingStatusService.apply(
                BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));

        assertThat(first).isEqualTo(MeetingStatusService.Result.RECORDING_STARTED);
        assertThat(redelivered).isEqualTo(MeetingStatusService.Result.APPLIED);
        assertThat(meeting.getStartedAt()).isEqualTo(CREATED_AT.plusSeconds(10));
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.RECORDING);
    }

    @Test
    void appliesIntermediateStatusesWithoutStartingRecording() {
        Meeting meeting = meeting();
        when(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID)).thenReturn(Optional.of(meeting));

        MeetingStatusService.Result result = meetingStatusService.apply(
                BOT_ID, MeetingStatus.JOINING, null, CREATED_AT.plusSeconds(5));

        assertThat(result).isEqualTo(MeetingStatusService.Result.APPLIED);
        assertThat(meeting.getStartedAt()).isNull();
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.JOINING);
    }

    @Test
    void ignoresEventsThatArriveOutOfOrder() {
        Meeting meeting = meeting();
        when(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID)).thenReturn(Optional.of(meeting));
        meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));

        MeetingStatusService.Result result = meetingStatusService.apply(
                BOT_ID, MeetingStatus.JOINING, null, CREATED_AT.plusSeconds(5));

        assertThat(result).isEqualTo(MeetingStatusService.Result.IGNORED);
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.RECORDING);
    }

    @Test
    void publishesBotLeftOnlyOnTheFirstDoneTransition() {
        Meeting meeting = meeting();
        when(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID)).thenReturn(Optional.of(meeting));
        meetingStatusService.apply(BOT_ID, MeetingStatus.FAILED, "bot_kicked", CREATED_AT.plusSeconds(20));

        MeetingStatusService.Result result = meetingStatusService.apply(
                BOT_ID, MeetingStatus.DONE, null, CREATED_AT.plusSeconds(30));
        MeetingStatusService.Result redelivered = meetingStatusService.apply(
                BOT_ID, MeetingStatus.DONE, null, CREATED_AT.plusSeconds(30));

        assertThat(result).isEqualTo(MeetingStatusService.Result.MEETING_ENDED);
        assertThat(redelivered).isEqualTo(MeetingStatusService.Result.APPLIED);
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.DONE);
        // fatal이 먼저 와도 최초 종료 시각을 덮어쓰지 않는다.
        assertThat(meeting.getEndedAt()).isEqualTo(CREATED_AT.plusSeconds(20));
        assertThat(realtimeEventsOfType(MeetingWorkspaceEvent.BOT_LEFT)).hasSize(1);
    }

    @Test
    void publishesBotJoinedWithTheRecordingTransitionPayload() {
        Meeting meeting = meeting();
        when(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID)).thenReturn(Optional.of(meeting));

        meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));
        meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));

        List<MeetingWorkspaceEvent> joined = realtimeEventsOfType(MeetingWorkspaceEvent.BOT_JOINED);
        assertThat(joined).hasSize(1);
        MeetingWorkspaceEvent payload = joined.get(0);
        assertThat(payload.workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(payload.userId()).isEqualTo(USER_ID);
        assertThat(payload.meetingId()).isEqualTo(meeting.getId());
        assertThat(payload.nodeId()).isEqualTo(NODE_ID);
        assertThat(payload.botId()).isEqualTo(BOT_ID);
        assertThat(payload.status()).isEqualTo("RECORDING");
        // 발행 시각이 아니라 상태가 발생한 시각이다.
        assertThat(payload.occurredAt()).isEqualTo(CREATED_AT.plusSeconds(10));
        assertThat(payload.statusSubCode()).isNull();
    }

    @Test
    void publishesBotFailedWithStatusSubCodeOnlyOnTheFirstFailure() {
        Meeting meeting = meeting();
        when(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID)).thenReturn(Optional.of(meeting));

        MeetingStatusService.Result result = meetingStatusService.apply(
                BOT_ID, MeetingStatus.FAILED, "meeting_not_found", CREATED_AT.plusSeconds(15));
        MeetingStatusService.Result redelivered = meetingStatusService.apply(
                BOT_ID, MeetingStatus.FAILED, "meeting_not_found", CREATED_AT.plusSeconds(15));

        assertThat(result).isEqualTo(MeetingStatusService.Result.MEETING_FAILED);
        assertThat(redelivered).isEqualTo(MeetingStatusService.Result.APPLIED);
        List<MeetingWorkspaceEvent> failed = realtimeEventsOfType(MeetingWorkspaceEvent.BOT_FAILED);
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0).statusSubCode()).isEqualTo("meeting_not_found");
        assertThat(failed.get(0).status()).isEqualTo("FAILED");
    }

    @Test
    void registersMeetingStartedEventOnlyOnTheFirstRecordingTransition() {
        Meeting meeting = meeting();
        when(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID)).thenReturn(Optional.of(meeting));

        meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));
        meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));
        meetingStatusService.apply(BOT_ID, MeetingStatus.DONE, null, CREATED_AT.plusSeconds(20));

        List<MeetingStartedEvent> started = publishedEvents(MeetingStartedEvent.class);
        assertThat(started).hasSize(1);
        MeetingStartedEvent published = started.get(0);
        assertThat(published.version()).isEqualTo(1);
        assertThat(published.type()).isEqualTo("MEETING_STARTED");
        assertThat(published.source()).isEqualTo("spring-api");
        assertThat(published.eventId()).isNotNull();
        // 발행 시각(NOW)이 아니라 녹음이 시작된 시각이다.
        assertThat(published.occurredAt()).isEqualTo(CREATED_AT.plusSeconds(10));
        assertThat(published.payload().meetingId()).isEqualTo(meeting.getId());
        assertThat(published.payload().nodeId()).isEqualTo(NODE_ID);
        assertThat(published.payload().workspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(published.payload().botId()).isEqualTo(BOT_ID);
    }

    @Test
    void doesNotRegisterEventForUnknownBotIgnoredOrNonRecordingStatuses() {
        Meeting meeting = meeting();
        when(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID)).thenReturn(Optional.of(meeting));

        meetingStatusService.apply(BOT_ID, MeetingStatus.JOINING, null, CREATED_AT.plusSeconds(5));
        meetingStatusService.apply(BOT_ID, MeetingStatus.WAITING_ROOM, null, CREATED_AT.plusSeconds(1));
        when(meetingRepository.findByBotIdAndDeletedAtIsNull(BOT_ID)).thenReturn(Optional.empty());
        meetingStatusService.apply(BOT_ID, MeetingStatus.RECORDING, null, CREATED_AT.plusSeconds(10));

        org.mockito.Mockito.verifyNoInteractions(applicationEventPublisher);
    }

    private Meeting meeting() {
        Meeting meeting = Meeting.request(
                WORKSPACE_ID, NODE_ID, USER_ID, "https://meet.google.com/abc-defg-hij", Bottype.GOOGLE, CREATED_AT);
        meeting.linkBot(BOT_ID, CREATED_AT);
        return meeting;
    }

    private <T> List<T> publishedEvents(Class<T> eventType) {
        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        org.mockito.Mockito.verify(applicationEventPublisher, org.mockito.Mockito.atLeast(0))
                .publishEvent(eventCaptor.capture());
        return eventCaptor.getAllValues().stream()
                .filter(eventType::isInstance)
                .map(eventType::cast)
                .toList();
    }

    private List<MeetingWorkspaceEvent> realtimeEventsOfType(String type) {
        return publishedEvents(MeetingRealtimeEvent.class).stream()
                .map(MeetingRealtimeEvent::payload)
                .filter(payload -> payload.type().equals(type))
                .toList();
    }
}
