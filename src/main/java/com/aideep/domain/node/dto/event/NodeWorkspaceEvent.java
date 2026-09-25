package com.aideep.domain.node.dto.event;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/** 기존 aideep-ws의 WORKSPACE_EVENT payload 계약. */
public sealed interface NodeWorkspaceEvent {
    String type();

    UUID workspaceId();

    String userId();

    record Created(String type, UUID workspaceId, String userId, NodeSnapshot node) implements NodeWorkspaceEvent {
    }

    record Updated(String type, UUID workspaceId, String userId, UUID nodeId, Patch patch)
            implements NodeWorkspaceEvent {
    }

    record NodeSnapshot(UUID nodeId, String title, String nodeType, NodePosition position, ObjectNode data,
                        Instant createdAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Patch(String title, String nodeType, ObjectNode data) {
    }
}
