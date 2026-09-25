package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeCreateCommand;
import com.aideep.domain.node.dto.event.NodeEventEnvelope;
import com.aideep.domain.node.dto.event.NodePatchCommand;
import com.aideep.domain.node.dto.event.NodePosition;
import com.aideep.domain.node.entity.Node;
import com.aideep.domain.node.entity.NodeType;
import com.aideep.domain.node.exception.NodeError;
import com.aideep.global.exception.BusinessException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * envelope payload를 노드 명령으로 변환한다. 계약 위반은 재시도로 해결되지 않으므로 영구 실패로 분류한다.
 */
@Component
public class NodeCommandPayloadParser {

    public NodeCreateCommand parseCreate(NodeEventEnvelope nodeEventEnvelope) {
        JsonNode payload = nodeEventEnvelope.payload();
        String title = requiredText(payload, "title");
        NodeType nodeType = requiredNodeType(payload, "nodeType");
        NodePosition position = requiredPosition(payload);
        ObjectNode data = requiredObject(payload, "data");
        if (title.length() > Node.MAX_TITLE_LENGTH) {
            throw permanent("title must not exceed " + Node.MAX_TITLE_LENGTH + " characters");
        }
        return new NodeCreateCommand(title, nodeType, position, data);
    }

    public NodePatchCommand parsePatch(NodeEventEnvelope nodeEventEnvelope) {
        JsonNode payload = nodeEventEnvelope.payload();
        UUID nodeId = requiredUuid(payload, "nodeId");
        int expectedVersion = requiredPositiveInt(payload, "expectedVersion");
        ObjectNode patch = requiredObject(payload, "patch");

        String title = optionalText(patch, "title");
        NodeType nodeType = patch.has("nodeType") ? requiredNodeType(patch, "nodeType") : null;
        ObjectNode data = patch.has("data") ? requiredObject(patch, "data") : null;

        NodePatchCommand nodePatchCommand = new NodePatchCommand(nodeId, expectedVersion, title, nodeType, data);
        if (nodePatchCommand.hasNoChange()) {
            throw permanent("patch must contain at least one of title, nodeType, data");
        }
        return nodePatchCommand;
    }

    private String requiredText(JsonNode parent, String fieldName) {
        JsonNode value = parent.get(fieldName);
        if (value == null || !value.isString()) {
            throw permanent(fieldName + " must be a string");
        }
        return value.asString();
    }

    private String optionalText(JsonNode parent, String fieldName) {
        return parent.has(fieldName) ? requiredText(parent, fieldName) : null;
    }

    private NodeType requiredNodeType(JsonNode parent, String fieldName) {
        String value = requiredText(parent, fieldName);
        try {
            return NodeType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(NodeError.UNSUPPORTED_NODE_TYPE,
                    "Unsupported node type: " + value);
        }
    }

    private NodePosition requiredPosition(JsonNode parent) {
        JsonNode position = parent.get("position");
        if (position == null || !position.isObject()) {
            throw permanent("position must be a JSON object");
        }
        return new NodePosition(requiredFiniteNumber(position, "x"), requiredFiniteNumber(position, "y"));
    }

    private double requiredFiniteNumber(JsonNode parent, String fieldName) {
        JsonNode value = parent.get(fieldName);
        if (value == null || !value.isNumber()) {
            throw permanent("position." + fieldName + " must be a number");
        }
        double number = value.asDouble();
        if (!Double.isFinite(number)) {
            throw permanent("position." + fieldName + " must be finite");
        }
        return number;
    }

    private ObjectNode requiredObject(JsonNode parent, String fieldName) {
        JsonNode value = parent.get(fieldName);
        if (value == null || !value.isObject()) {
            throw permanent(fieldName + " must be a JSON object");
        }
        return (ObjectNode) value;
    }

    private UUID requiredUuid(JsonNode parent, String fieldName) {
        String value = requiredText(parent, fieldName);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw permanent(fieldName + " must be a UUID");
        }
    }

    private int requiredPositiveInt(JsonNode parent, String fieldName) {
        JsonNode value = parent.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() <= 0) {
            throw permanent(fieldName + " must be a positive integer");
        }
        return value.asInt();
    }

    private BusinessException permanent(String message) {
        return new BusinessException(NodeError.INVALID_PAYLOAD, message);
    }
}
