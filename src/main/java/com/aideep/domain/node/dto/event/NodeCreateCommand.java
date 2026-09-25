package com.aideep.domain.node.dto.event;

import com.aideep.domain.node.entity.NodeType;
import tools.jackson.databind.node.ObjectNode;

public record NodeCreateCommand(String title, NodeType nodeType, NodePosition position, ObjectNode data) {
}
