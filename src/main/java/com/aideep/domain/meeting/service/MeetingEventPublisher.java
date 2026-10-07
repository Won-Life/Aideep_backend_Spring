package com.aideep.domain.meeting.service;

import com.aideep.domain.meeting.config.MeetingEventProperties;
import com.aideep.domain.meeting.dto.event.MeetingStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * 커밋된 회의 상태와 분리된 best-effort 통지. outbox도 재전송도 없으므로 발행 실패나 Redis 장애로 이벤트가 유실될 수 있다.
 * <p>
 * 발행 실패로 이미 커밋된 상태 반영을 되돌리거나 웹훅 응답을 실패로 바꾸지 않는다. Recall이 같은 웹훅을 재시도해도 녹음 시작은 한 번만 기록되므로 이벤트가
 * 다시 만들어지지도 않는다.
 */
@Service
public class MeetingEventPublisher {

    private static final String DATA_FIELD = "data";
    private static final Logger log = LoggerFactory.getLogger(MeetingEventPublisher.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final MeetingEventProperties meetingEventProperties;
    private final ObjectMapper objectMapper;

    public MeetingEventPublisher(StringRedisTemplate stringRedisTemplate,
                                 MeetingEventProperties meetingEventProperties,
                                 ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.meetingEventProperties = meetingEventProperties;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(MeetingStartedEvent meetingStartedEvent) {
        if (!meetingEventProperties.enabled()) {
            log.info("회의 이벤트 발행이 비활성화되어 있습니다. eventId={} meetingId={}",
                    meetingStartedEvent.eventId(), meetingStartedEvent.payload().meetingId());
            return;
        }
        try {
            var recordId = stringRedisTemplate.opsForStream().add(meetingEventProperties.streamKey(),
                    Map.of(DATA_FIELD, objectMapper.writeValueAsString(meetingStartedEvent)));
            // XADD 수락일 뿐이며 AI 서버의 수신을 보장하지 않는다.
            log.info("회의 시작 이벤트를 발행했습니다. stream={} entryId={} eventId={} meetingId={} botId={}",
                    meetingEventProperties.streamKey(), recordId == null ? null : recordId.getValue(),
                    meetingStartedEvent.eventId(), meetingStartedEvent.payload().meetingId(),
                    meetingStartedEvent.payload().botId());
        } catch (RuntimeException exception) {
            log.error("회의 시작 이벤트 발행에 실패했습니다. stream={} eventId={} meetingId={}",
                    meetingEventProperties.streamKey(), meetingStartedEvent.eventId(),
                    meetingStartedEvent.payload().meetingId(), exception);
        }
    }
}
