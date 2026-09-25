package com.aideep.domain.node.dto.event;

import com.aideep.domain.node.entity.NodeType;
import tools.jackson.databind.node.ObjectNode;

import java.util.UUID;

public record NodePatchCommand(UUID nodeId, int expectedVersion, String title, NodeType nodeType, ObjectNode data) {

    public boolean hasNoChange() {
        return title == null && nodeType == null && data == null;
    }
}
