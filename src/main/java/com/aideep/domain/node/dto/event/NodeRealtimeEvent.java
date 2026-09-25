package com.aideep.domain.node.dto.event;

import java.util.UUID;

/** 트랜잭션 커밋 후 발행할 스냅샷과 원본 명령의 추적 ID. */
public record NodeRealtimeEvent(UUID eventId, NodeWorkspaceEvent payload) {
}
