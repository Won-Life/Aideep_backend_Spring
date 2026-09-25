package com.aideep.domain.node.dto.event;

import java.time.Instant;
import java.util.UUID;

public record NodeEventBusEnvelope(int v, String kind, UUID messageId, Instant publishedAt, String origin,
                                   NodeWorkspaceEvent payload) {
}
