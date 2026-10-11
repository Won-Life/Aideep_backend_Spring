package com.aideep.domain.node.dto.event;

import java.util.List;
import java.util.UUID;

/**
 * 트랜잭션 커밋 후 발행할 스냅샷과 원본 명령의 추적 ID.
 * 하나의 명령이 여러 WS 이벤트를 만들 수 있으므로({@code NODE_CREATE} 다음 {@code EDGE_CREATE})
 * {@code payloads}의 순서가 그대로 발행 순서다.
 */
public record NodeRealtimeEvent(UUID eventId, UUID workspaceId, List<NodeWorkspaceEvent> payloads) {
}
