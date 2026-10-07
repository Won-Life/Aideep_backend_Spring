package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.entity.MeetingStatus;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Recall 봇 상태 코드를 도메인 상태로 변환한다.
 * <p>
 * Recall은 상태 코드를 계속 추가하므로 집합을 닫힌 것으로 다루지 않는다. 매핑하지 않은 코드는 상태를 바꾸지 않고 호출자가 무시한다.
 */
@Component
public class RecallStatusMapper {

    private static final Map<String, MeetingStatus> STATUSES = Map.of(
            "joining_call", MeetingStatus.JOINING,
            "in_waiting_room", MeetingStatus.WAITING_ROOM,
            "in_call_not_recording", MeetingStatus.IN_CALL_NOT_RECORDING,
            "in_call_recording", MeetingStatus.RECORDING,
            "call_ended", MeetingStatus.CALL_ENDED,
            "done", MeetingStatus.DONE,
            "fatal", MeetingStatus.FAILED);

    public Optional<MeetingStatus> map(String code) {
        if (code == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(STATUSES.get(code));
    }
}
