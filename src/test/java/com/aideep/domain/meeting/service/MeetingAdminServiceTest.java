package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.entity.Bottype;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.exception.MeetingError;
import com.aideep.domain.meeting.repository.MeetingRepository;
import com.aideep.global.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MeetingAdminServiceTest {

    private static final UUID MEETING_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID BOT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant CREATED_AT = Instant.parse("2026-10-14T04:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-14T05:30:00Z");

    private final MeetingRepository meetingRepository = mock(MeetingRepository.class);
    private final RecallBotClient recallBotClient = mock(RecallBotClient.class);
    private final MeetingAdminService meetingAdminService = new MeetingAdminService(meetingRepository,
            recallBotClient, new RecallStatusMapper(), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void asksRecallToRemoveTheBotWithoutTouchingTheRow() {
        Meeting meeting = given(MeetingStatus.RECORDING, BOT_ID);

        meetingAdminService.forceLeave(MEETING_ID);

        verify(recallBotClient).leaveCall(BOT_ID);
        // 상태 전이는 웹훅 경로가 단독으로 처리한다. 여기서 미리 쓰면 두 경로가 같은 행을 다툰다.
        verify(meetingRepository, never()).save(any());
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.RECORDING);
        assertThat(meeting.getEndedAt()).isNull();
    }

    @Test
    void refusesToRemoveABotFromAnAlreadyEndedMeeting() {
        given(MeetingStatus.DONE, BOT_ID);

        assertThatThrownBy(() -> meetingAdminService.forceLeave(MEETING_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(MeetingError.MEETING_ALREADY_ENDED);
        verifyNoInteractions(recallBotClient);
    }

    @Test
    void refusesToRemoveABotThatWasNeverLinked() {
        given(MeetingStatus.REQUESTED, null);

        assertThatThrownBy(() -> meetingAdminService.forceLeave(MEETING_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(MeetingError.BOT_NOT_LINKED);
        verifyNoInteractions(recallBotClient);
    }

    @Test
    void closesARowThatHasNoBotSoTheActiveListAndUrlConstraintAreFreed() {
        Meeting meeting = given(MeetingStatus.REQUESTED, null);

        meetingAdminService.closeRecord(MEETING_ID);

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.DONE);
        assertThat(meeting.getStatus().isTerminal()).isTrue();
        assertThat(meeting.getStatusSubCode()).isEqualTo(MeetingAdminService.FORCED_CLOSE_SUB_CODE);
        assertThat(meeting.getEndedAt()).isEqualTo(NOW);
        verify(meetingRepository).save(meeting);
        verifyNoInteractions(recallBotClient);
    }

    @Test
    void refusesToCloseTheRecordWhileABotIsStillLinked() {
        Meeting meeting = given(MeetingStatus.RECORDING, BOT_ID);

        assertThatThrownBy(() -> meetingAdminService.closeRecord(MEETING_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(MeetingError.BOT_STILL_LINKED);
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.RECORDING);
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void reportsAMissingMeetingAsNotFound() {
        when(meetingRepository.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingAdminService.forceLeave(MEETING_ID))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getErrorCode())
                .isEqualTo(MeetingError.MEETING_NOT_FOUND);
    }

    @Test
    void pullsTheLastStatusFromRecallWhenTheWebhookWasLost() {
        Meeting meeting = given(MeetingStatus.REQUESTED, BOT_ID);
        Instant endedAt = Instant.parse("2026-10-14T05:04:00Z");
        when(recallBotClient.fetchStatus(BOT_ID))
                .thenReturn(Optional.of(new RecallBotClient.RecallBotStatus("done", null, endedAt)));

        assertThat(meetingAdminService.syncStatus(MEETING_ID)).isTrue();

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.DONE);
        assertThat(meeting.getEndedAt()).isEqualTo(endedAt);
        verify(meetingRepository).save(meeting);
    }

    /** 호스트가 줌을 꺼버린 회의는 call_ended 뒤 done, 그리고 30분쯤 뒤 media_expired로 끝난다. */
    @Test
    void treatsMediaExpiredAsAFinishedMeeting() {
        Meeting meeting = given(MeetingStatus.REQUESTED, BOT_ID);
        Instant expiredAt = Instant.parse("2026-10-14T05:16:53Z");
        when(recallBotClient.fetchStatus(BOT_ID))
                .thenReturn(Optional.of(new RecallBotClient.RecallBotStatus("media_expired", null, expiredAt)));

        assertThat(meetingAdminService.syncStatus(MEETING_ID)).isTrue();

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.DONE);
    }

    @Test
    void leavesTheRowAloneWhenRecallReportsACodeWeDoNotMap() {
        Meeting meeting = given(MeetingStatus.RECORDING, BOT_ID);
        when(recallBotClient.fetchStatus(BOT_ID)).thenReturn(
                Optional.of(new RecallBotClient.RecallBotStatus("recording_done", null, NOW)));

        assertThat(meetingAdminService.syncStatus(MEETING_ID)).isFalse();

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.RECORDING);
        verify(meetingRepository, never()).save(any());
    }

    /** 조회 결과가 이미 반영한 이벤트보다 과거면 웹훅과 같은 관문에서 막혀야 한다. */
    @Test
    void ignoresAStatusOlderThanTheEventAlreadyApplied() {
        Meeting meeting = given(MeetingStatus.RECORDING, BOT_ID);
        when(recallBotClient.fetchStatus(BOT_ID)).thenReturn(Optional.of(
                new RecallBotClient.RecallBotStatus("joining_call", null, CREATED_AT.minusSeconds(60))));

        assertThat(meetingAdminService.syncStatus(MEETING_ID)).isFalse();

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.RECORDING);
        verify(meetingRepository, never()).save(any());
    }

    @Test
    void sweepsOnlyRowsThatHaveBeenQuietForTooLongAndKeepsGoingAfterOneFails() {
        Meeting failing = Meeting.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "https://meet.test/one", Bottype.ZOOM, CREATED_AT);
        failing.linkBot(UUID.randomUUID(), CREATED_AT);
        Meeting recovering = Meeting.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "https://meet.test/two", Bottype.ZOOM, CREATED_AT);
        recovering.linkBot(BOT_ID, CREATED_AT);
        when(meetingRepository.findStalled(MeetingStatus.active(), NOW.minus(Duration.ofMinutes(10))))
                .thenReturn(List.of(failing, recovering));
        when(recallBotClient.fetchStatus(failing.getBotId()))
                .thenThrow(new BusinessException(MeetingError.RECALL_UNAVAILABLE));
        when(recallBotClient.fetchStatus(BOT_ID)).thenReturn(
                Optional.of(new RecallBotClient.RecallBotStatus("done", null, NOW.minusSeconds(600))));

        assertThat(meetingAdminService.syncStalled(Duration.ofMinutes(10))).isEqualTo(1);

        assertThat(recovering.getStatus()).isEqualTo(MeetingStatus.DONE);
        assertThat(failing.getStatus()).isEqualTo(MeetingStatus.REQUESTED);
    }

    private Meeting given(MeetingStatus status, UUID botId) {
        Meeting meeting = Meeting.request(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "https://meet.test/abc", Bottype.GOOGLE, CREATED_AT);
        if (botId != null) {
            meeting.linkBot(botId, CREATED_AT);
        }
        if (status != MeetingStatus.REQUESTED) {
            meeting.applyStatus(status, null, CREATED_AT, CREATED_AT);
        }
        when(meetingRepository.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.of(meeting));
        return meeting;
    }
}
