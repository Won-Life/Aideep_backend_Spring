package com.aideep.domain.meeting.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeetingTest {
    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID NODE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID BOT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private Meeting requested() {
        return Meeting.request(WORKSPACE_ID, NODE_ID, USER_ID, "https://meet.test/abc", Bottype.GOOGLE, NOW);
    }

    @Test
    void startsAsRequestedWithoutBotAndTimes() {
        Meeting meeting = requested();

        assertThat(meeting.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
        assertThat(meeting.getNodeId()).isEqualTo(NODE_ID);
        assertThat(meeting.getUserId()).isEqualTo(USER_ID);
        assertThat(meeting.getMeetingUrl()).isEqualTo("https://meet.test/abc");
        assertThat(meeting.getBotType()).isEqualTo(Bottype.GOOGLE);
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.REQUESTED);
        assertThat(meeting.getBotId()).isNull();
        assertThat(meeting.getStartedAt()).isNull();
        assertThat(meeting.getEndedAt()).isNull();
    }

    @Test
    void linksBotIdReturnedByRecall() {
        Meeting meeting = requested();

        meeting.linkBot(BOT_ID, NOW.plusSeconds(1));

        assertThat(meeting.getBotId()).isEqualTo(BOT_ID);
        assertThat(meeting.getUpdatedAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void recordingStatusSetsStartedAtOnce() {
        Meeting meeting = requested();
        Instant firstRecording = NOW.plusSeconds(30);

        meeting.applyStatus(MeetingStatus.RECORDING, null, firstRecording, NOW.plusSeconds(31));
        meeting.applyStatus(MeetingStatus.RECORDING, null, firstRecording.plusSeconds(10), NOW.plusSeconds(41));

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.RECORDING);
        assertThat(meeting.getStartedAt()).isEqualTo(firstRecording);
        assertThat(meeting.getEndedAt()).isNull();
    }

    @Test
    void terminalStatusSetsEndedAt() {
        Meeting meeting = requested();
        meeting.applyStatus(MeetingStatus.RECORDING, null, NOW.plusSeconds(30), NOW.plusSeconds(31));
        Instant callEnded = NOW.plusSeconds(600);

        meeting.applyStatus(MeetingStatus.CALL_ENDED, "meeting_ended_by_host", callEnded, NOW.plusSeconds(601));

        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.CALL_ENDED);
        assertThat(meeting.getEndedAt()).isEqualTo(callEnded);
        assertThat(meeting.getStatusSubCode()).isEqualTo("meeting_ended_by_host");
    }

    @Test
    void ignoresEventOlderThanLastAppliedEvent() {
        Meeting meeting = requested();
        Instant recent = NOW.plusSeconds(600);
        meeting.applyStatus(MeetingStatus.CALL_ENDED, null, recent, NOW.plusSeconds(601));

        boolean applied = meeting.applyStatus(MeetingStatus.JOINING, null, NOW.plusSeconds(10), NOW.plusSeconds(602));

        assertThat(applied).isFalse();
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.CALL_ENDED);
        assertThat(meeting.getEndedAt()).isEqualTo(recent);
    }

    @Test
    void failedStatusAlsoEndsMeeting() {
        Meeting meeting = requested();
        Instant failedAt = NOW.plusSeconds(20);

        boolean applied = meeting.applyStatus(MeetingStatus.FAILED, "meeting_not_found", failedAt,
                NOW.plusSeconds(21));

        assertThat(applied).isTrue();
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.FAILED);
        assertThat(meeting.getEndedAt()).isEqualTo(failedAt);
        assertThat(meeting.getStartedAt()).isNull();
    }
}
