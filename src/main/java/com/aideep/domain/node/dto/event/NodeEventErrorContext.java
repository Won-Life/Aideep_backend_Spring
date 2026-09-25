package com.aideep.domain.node.dto.event;

/** envelope를 만들지 못한 경우에도 DLQ 추적 정보를 보존한다. */
public record NodeEventErrorContext(String eventId, String eventType, String detail) {
}
