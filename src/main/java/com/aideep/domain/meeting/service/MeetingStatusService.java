package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.dto.event.MeetingRealtimeEvent;
import com.aideep.domain.meeting.dto.event.MeetingStartedEvent;
import com.aideep.domain.meeting.dto.event.MeetingWorkspaceEvent;
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
        // 최초 전이 판정은 applyStatus가 상태를 덮어쓰기 전에 읽어야 한다.
        MeetingStatus previousStatus = meeting.getStatus();
        boolean recordingNotStarted = meeting.getStartedAt() == null;
        if (!meeting.applyStatus(status, statusSubCode, occurredAt, clock.instant())) {
            return Result.IGNORED;
        }
        meetingRepository.save(meeting);
        // 커밋 후에만 발행한다. 롤백되면 AI 서버도 WS 서버도 일어나지 않은 전이를 통지받지 않는다.
        if (status == MeetingStatus.RECORDING && recordingNotStarted) {
            applicationEventPublisher.publishEvent(MeetingStartedEvent.of(UUID.randomUUID(), occurredAt,
                    meeting.getId(), meeting.getNodeId(), meeting.getWorkspaceId(), meeting.getBotId()));
            publishRealtime(MeetingWorkspaceEvent.botJoined(meeting, occurredAt));
            return Result.RECORDING_STARTED;
        }
        if (status == MeetingStatus.DONE && previousStatus != MeetingStatus.DONE) {
            publishRealtime(MeetingWorkspaceEvent.botLeft(meeting, occurredAt));
            return Result.MEETING_ENDED;
        }
        if (status == MeetingStatus.FAILED && previousStatus != MeetingStatus.FAILED) {
            publishRealtime(MeetingWorkspaceEvent.botFailed(meeting, occurredAt));
            return Result.MEETING_FAILED;
        }
        return Result.APPLIED;
    }

    private void publishRealtime(MeetingWorkspaceEvent meetingWorkspaceEvent) {
        applicationEventPublisher.publishEvent(MeetingRealtimeEvent.of(meetingWorkspaceEvent));
    }

    /**
     * {@code RECORDING_STARTED}, {@code MEETING_ENDED}, {@code MEETING_FAILED}는 각 상태로의 최초 전이다. 재전송된 같은
     * 웹훅은 이미 상태가 반영되어 있어 {@code APPLIED}가 되므로, 통지는 이 값들로만 한 번씩 발행한다.
     */
    public enum Result {
        UNKNOWN_BOT,
        IGNORED,
        APPLIED,
        RECORDING_STARTED,
        MEETING_ENDED,
        MEETING_FAILED
    }
}
