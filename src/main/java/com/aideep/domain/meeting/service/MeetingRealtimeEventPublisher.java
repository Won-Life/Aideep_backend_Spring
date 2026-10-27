package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.dto.event.MeetingRealtimeEvent;
import com.aideep.global.event.RealtimeEventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.UUID;

/**
 * 커밋된 회의 상태와 분리된 best-effort 실시간 알림. WS 연결과 room 라우팅은 {@code aideep-ws}가 담당한다.
 * <p>
 * AI 서버용 {@link MeetingEventPublisher}와 달리 Redis Pub/Sub이므로 구독자가 없으면 메시지가 사라진다. 재접속 복구는 회의 조회
 * API가 담당하므로 여기서 재전송하지 않는다. 발행 실패는 이미 커밋된 상태나 웹훅 200 응답을 되돌리지 않는다.
 */
@Service
public class MeetingRealtimeEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(MeetingRealtimeEventPublisher.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public MeetingRealtimeEventPublisher(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper,
                                         Clock clock) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(MeetingRealtimeEvent meetingRealtimeEvent) {
        var payload = meetingRealtimeEvent.payload();
        try {
            var envelope = RealtimeEventEnvelope.workspaceEvent(UUID.randomUUID(), clock.instant(), payload);
            stringRedisTemplate.convertAndSend(RealtimeEventEnvelope.CHANNEL,
                    objectMapper.writeValueAsString(envelope));
            log.info("회의 실시간 이벤트를 발행했습니다. channel={} eventId={} type={} meetingId={} workspaceId={}",
                    RealtimeEventEnvelope.CHANNEL, meetingRealtimeEvent.eventId(), payload.type(),
                    payload.meetingId(), payload.workspaceId());
        } catch (RuntimeException exception) {
            log.error("회의 실시간 이벤트 발행에 실패했습니다. channel={} eventId={} type={} meetingId={}",
                    RealtimeEventEnvelope.CHANNEL, meetingRealtimeEvent.eventId(), payload.type(),
                    payload.meetingId(), exception);
        }
    }
}
