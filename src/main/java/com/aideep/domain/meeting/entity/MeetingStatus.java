package com.aideep.domain.meeting.entity;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Recall 봇 생명주기를 백엔드 관점으로 축약한 회의 상태.
 * Recall의 원본 status code/sub_code는 별도 컬럼에 그대로 보존한다.
 */
public enum MeetingStatus {
    REQUESTED,
    JOINING,
    WAITING_ROOM,
    IN_CALL_NOT_RECORDING,
    RECORDING,
    CALL_ENDED,
    DONE,
    FAILED;

    public boolean isTerminal() {
        return this == CALL_ENDED || this == DONE || this == FAILED;
    }

    /**
     * 봇이 아직 회의에 붙어 있는 상태들. DB의 {@code uq_meetings_active_url} 부분 인덱스 조건과 같은 집합이어야 한다.
     */
    public static Set<MeetingStatus> active() {
        return ACTIVE;
    }

    private static final Set<MeetingStatus> ACTIVE = Collections.unmodifiableSet(
            EnumSet.allOf(MeetingStatus.class).stream()
                    .filter(status -> !status.isTerminal())
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(MeetingStatus.class))));
}
