package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeBodyPatch;
import com.aideep.domain.node.dto.event.NodeRealtimeEvent;
import com.aideep.domain.node.dto.event.NodeWorkspaceEvent;
import com.aideep.global.event.RealtimeEventEnvelope;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * DB 성공과 분리된 best-effort 실시간 알림. WS 연결과 room 라우팅은 aideep-ws가 담당한다.
 * 한 명령이 만든 payload들은 받은 순서대로 발행하며(NODE_CREATE → EDGE_CREATE),
 * 하나가 실패해도 나머지 발행을 멈추지 않는다.
 */
@Service
public class NodeEventBusPublisher {
    public static final String CHANNEL = RealtimeEventEnvelope.CHANNEL;
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
        UUID workspaceId = nodeRealtimeEvent.workspaceId();
        try {
            stringRedisTemplate.delete("workspace:sync:" + workspaceId);
        } catch (RuntimeException exception) {
            log.error("Workspace cache invalidation failed. eventId={} workspaceId={}",
                    nodeRealtimeEvent.eventId(), workspaceId, exception);
        }
        for (NodeWorkspaceEvent nodeWorkspaceEvent : nodeRealtimeEvent.payloads()) {
            try {
                var realtimeEventEnvelope = RealtimeEventEnvelope.workspaceEvent(
                        UUID.randomUUID(), clock.instant(), nodeWorkspaceEvent);
                stringRedisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(realtimeEventEnvelope));
            } catch (RuntimeException exception) {
                log.error("Realtime event publication failed. eventId={} workspaceId={} type={}",
                        nodeRealtimeEvent.eventId(), workspaceId, nodeWorkspaceEvent.type(), exception);
            }
        }
        publishBodyPatch(nodeRealtimeEvent);
    }

    /**
     * 본문 반영 명령. 실패하면 본문만 빠진 채로 남는다. DB 패치는 이미 커밋됐고 재시도도 같은 pub/sub이라 여기서 되돌리지 않고 기록만 남긴다.
     */
    private void publishBodyPatch(NodeRealtimeEvent nodeRealtimeEvent) {
        NodeBodyPatch nodeBodyPatch = nodeRealtimeEvent.bodyPatch();
        if (nodeBodyPatch == null) {
            return;
        }
        try {
            var realtimeEventEnvelope = RealtimeEventEnvelope.nodeBodyPatch(
                    UUID.randomUUID(), clock.instant(), nodeBodyPatch);
            stringRedisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(realtimeEventEnvelope));
        } catch (RuntimeException exception) {
            log.error("Node body patch publication failed. eventId={} workspaceId={} nodeId={}",
                    nodeRealtimeEvent.eventId(), nodeBodyPatch.workspaceId(), nodeBodyPatch.nodeId(), exception);
        }
    }
}
