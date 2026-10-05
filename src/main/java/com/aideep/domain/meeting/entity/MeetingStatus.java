package com.aideep.domain.meeting.entity;

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
}
