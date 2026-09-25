package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeEventBusEnvelope;
import com.aideep.domain.node.dto.event.NodeRealtimeEvent;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/** DB 성공과 분리된 best-effort 실시간 알림. WS 연결과 room 라우팅은 aideep-ws가 담당한다. */
@Service
public class NodeEventBusPublisher {
    public static final String CHANNEL = "aideep.realtime.v1";
    private static final Logger log = LoggerFactory.getLogger(NodeEventBusPublisher.class);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NodeEventBusPublisher(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper, Clock clock) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(NodeRealtimeEvent nodeRealtimeEvent) {
        UUID workspaceId = nodeRealtimeEvent.payload().workspaceId();
        try {
            stringRedisTemplate.delete("workspace:sync:" + workspaceId);
        } catch (RuntimeException exception) {
            log.error("Workspace cache invalidation failed. eventId={} workspaceId={}",
                    nodeRealtimeEvent.eventId(), workspaceId, exception);
        }
        try {
            NodeEventBusEnvelope nodeEventBusEnvelope = new NodeEventBusEnvelope(1, "WORKSPACE_EVENT",
                    UUID.randomUUID(), clock.instant(), "spring-api", nodeRealtimeEvent.payload());
            stringRedisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(nodeEventBusEnvelope));
        } catch (RuntimeException exception) {
            log.error("Realtime event publication failed. eventId={} workspaceId={} type={}",
                    nodeRealtimeEvent.eventId(), workspaceId, nodeRealtimeEvent.payload().type(), exception);
        }
    }
}
