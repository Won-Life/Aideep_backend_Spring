package com.aideep.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aideep.domain.meeting.entity.MeetingStatus;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class RecallStatusMapperTest {

    private final RecallStatusMapper recallStatusMapper = new RecallStatusMapper();

    @Test
    void mapsEveryStatusTheDomainTracks() {
        Map<String, MeetingStatus> expected = Map.of(
                "joining_call", MeetingStatus.JOINING,
                "in_waiting_room", MeetingStatus.WAITING_ROOM,
                "in_call_not_recording", MeetingStatus.IN_CALL_NOT_RECORDING,
                "in_call_recording", MeetingStatus.RECORDING,
                "call_ended", MeetingStatus.CALL_ENDED,
                "done", MeetingStatus.DONE,
                "media_expired", MeetingStatus.DONE,
                "fatal", MeetingStatus.FAILED);

        expected.forEach((code, status) -> assertThat(recallStatusMapper.map(code)).contains(status));
    }

    @Test
    void ignoresCodesTheDomainDoesNotTrack() {
        assertThat(recallStatusMapper.map("ready")).isEmpty();
        assertThat(recallStatusMapper.map("recording_permission_allowed")).isEmpty();
        assertThat(recallStatusMapper.map("recording_permission_denied")).isEmpty();
        assertThat(recallStatusMapper.map("analysis_done")).isEmpty();
        assertThat(recallStatusMapper.map("analysis_failed")).isEmpty();
        assertThat(recallStatusMapper.map("recording_done")).isEmpty();
        assertThat(recallStatusMapper.map("a_code_recall_adds_later")).isEmpty();
    }

    /**
     * media_expired는 녹화 보관이 만료될 때 성공한 회의에도 찍히며 항상 done 뒤에 온다. done 웹훅을 놓친 회의를 닫아주는 안전망이라
     * 종료로 다룬다.
     */
    @Test
    void treatsMediaExpiryAsTheEndOfAMeeting() {
        assertThat(recallStatusMapper.map("media_expired")).contains(MeetingStatus.DONE);
        assertThat(MeetingStatus.DONE.isTerminal()).isTrue();
    }

    @Test
    void ignoresMissingCode() {
        assertThat(recallStatusMapper.map(null)).isEqualTo(Optional.empty());
    }
}
