package com.aideep.domain.node.dto.event;

import com.aideep.domain.node.entity.NodeType;
import java.util.UUID;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code parentNodeId}가 있으면 부모 → 새 노드 방향의 edge를 노드와 같은 트랜잭션에서 만든다.
 */
public record NodeCreateCommand(String title, NodeType nodeType, NodePosition position, ObjectNode data,
                                UUID parentNodeId, String sourceHandle, String targetHandle) {
}
