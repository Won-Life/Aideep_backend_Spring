package com.aideep.domain.node.dto.event;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record NodeEventEnvelope(int version, UUID eventId, NodeEventType eventType, Instant occurredAt,
                                UUID workspaceId, JsonNode payload) {
}
