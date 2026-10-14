package com.aideep.domain.meeting.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 웹훅을 놓친 회의를 주기적으로 Recall 기준에 맞춘다.
 * <p>
 * 상태의 정상 입력은 웹훅이고 이 스위퍼는 그물이다. 웹훅이 끊긴 구간의 회의는 과거 이벤트가 재전송되지 않아 스스로 닫히지 못하므로, 조용한 행만 골라
 * Recall에 직접 물어본다. 주기보다 넉넉한 정체 기준을 두어 정상적으로 진행 중인 회의를 건드리지 않는다.
 */
@Component
@ConditionalOnProperty(name = "meeting.sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class MeetingStatusSweeper {

    /** 이 시간 넘게 아무 이벤트도 없는 활성 회의만 확인한다. 봇은 보통 몇 초 간격으로 상태를 바꾼다. */
    private static final Duration STALL_THRESHOLD = Duration.ofMinutes(10);

    private final MeetingAdminService meetingAdminService;

    public MeetingStatusSweeper(MeetingAdminService meetingAdminService) {
        this.meetingAdminService = meetingAdminService;
    }

    @Scheduled(fixedDelayString = "${meeting.sweeper.interval-ms:300000}")
    public void sweep() {
        meetingAdminService.syncStalled(STALL_THRESHOLD);
    }
}
