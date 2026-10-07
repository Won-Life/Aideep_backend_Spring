package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.dto.event.MeetingStartedEvent;
import com.aideep.domain.meeting.entity.Meeting;
import com.aideep.domain.meeting.entity.MeetingStatus;
import com.aideep.domain.meeting.repository.MeetingRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Recall 봇 상태 이벤트를 회의에 반영한다. 재시도와 순서가 뒤바뀐 전달이 정상이므로 반영은 멱등해야 한다.
 */
@Service
public class MeetingStatusService {

    private final MeetingRepository meetingRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final Clock clock;

    public MeetingStatusService(MeetingRepository meetingRepository,
                                ApplicationEventPublisher applicationEventPublisher,
                                Clock clock) {
        this.meetingRepository = meetingRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.clock = clock;
    }

    @Transactional
    public Result apply(UUID botId, MeetingStatus status, String statusSubCode, Instant occurredAt) {
        Optional<Meeting> found = meetingRepository.findByBotIdAndDeletedAtIsNull(botId);
        if (found.isEmpty()) {
            return Result.UNKNOWN_BOT;
        }
        Meeting meeting = found.get();
        boolean recordingNotStarted = meeting.getStartedAt() == null;
        if (!meeting.applyStatus(status, statusSubCode, occurredAt, clock.instant())) {
            return Result.IGNORED;
        }
        meetingRepository.save(meeting);
        if (!recordingNotStarted || status != MeetingStatus.RECORDING) {
            return Result.APPLIED;
        }
        // 커밋 후에만 발행한다. 롤백되면 AI 서버는 시작하지 않은 회의를 통지받지 않는다.
        applicationEventPublisher.publishEvent(MeetingStartedEvent.of(UUID.randomUUID(), occurredAt,
                meeting.getId(), meeting.getNodeId(), meeting.getWorkspaceId(), meeting.getBotId()));
        return Result.RECORDING_STARTED;
    }

    /**
     * {@code RECORDING_STARTED}는 녹음 시작의 최초 전이다. 재전송된 같은 이벤트는 이미 {@code started_at}이 채워져 있어
     * {@code APPLIED}가 되므로, 회의 시작 통지는 이 값으로만 한 번 발행한다.
     */
    public enum Result {
        UNKNOWN_BOT,
        IGNORED,
        APPLIED,
        RECORDING_STARTED
    }
}
