package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeEventEnvelope;
import com.aideep.domain.node.dto.event.NodeEventErrorContext;
import com.aideep.domain.node.dto.event.NodeEventType;
import com.aideep.domain.node.exception.NodeError;
import com.aideep.global.exception.BusinessException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class NodeEventParser {
    private static final int SUPPORTED_VERSION = 1;

    private final ObjectMapper objectMapper;

    public NodeEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NodeEventEnvelope parse(String data) {
        JsonNode root;
        try {
            root = objectMapper.readTree(data);
        } catch (RuntimeException exception) {
            BusinessException businessException = failure(NodeError.INVALID_JSON,
                    "Node event data is not valid JSON", null, null);
            businessException.initCause(exception);
            throw businessException;
        }
        if (root == null || !root.isObject()) {
            throw invalidEnvelope("Node event must be a JSON object", null, null);
        }

        String eventIdValue = textValue(root, "eventId");
        String eventTypeValue = textValue(root, "eventType");
        int version = integerValue(root, "version", eventIdValue, eventTypeValue);
        if (version != SUPPORTED_VERSION) {
            throw failure(NodeError.UNSUPPORTED_VERSION, "Unsupported node event version: " + version,
                    eventIdValue, eventTypeValue);
        }

        UUID eventId = uuidValue(eventIdValue, "eventId", eventIdValue, eventTypeValue);
        NodeEventType eventType = eventTypeValue(eventTypeValue, eventIdValue);
        Instant occurredAt = instantValue(textValue(root, "occurredAt"), eventIdValue, eventTypeValue);
        UUID workspaceId = uuidValue(textValue(root, "workspaceId"), "workspaceId", eventIdValue, eventTypeValue);
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) {
            throw invalidEnvelope("payload must be a JSON object", eventIdValue, eventTypeValue);
        }
        return new NodeEventEnvelope(version, eventId, eventType, occurredAt, workspaceId, payload);
    }

    private String textValue(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw invalidEnvelope(fieldName + " must be a non-blank string", safeText(root, "eventId"),
                    safeText(root, "eventType"));
        }
        return value.asString();
    }

    private int integerValue(JsonNode root, String fieldName, String eventId, String eventType) {
        JsonNode value = root.get(fieldName);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidEnvelope(fieldName + " must be an integer", eventId, eventType);
        }
        return value.asInt();
    }

    private UUID uuidValue(String value, String fieldName, String eventId, String eventType) {
        try {
            UUID uuid = UUID.fromString(value);
            if (!uuid.toString().equalsIgnoreCase(value)) {
                throw invalidEnvelope(fieldName + " must be a canonical UUID", eventId, eventType);
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw invalidEnvelope(fieldName + " must be a UUID", eventId, eventType);
        }
    }

    private Instant instantValue(String value, String eventId, String eventType) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw invalidEnvelope("occurredAt must be an ISO-8601 instant", eventId, eventType);
        }
    }

    private NodeEventType eventTypeValue(String value, String eventId) {
        try {
            return NodeEventType.valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw failure(NodeError.UNSUPPORTED_EVENT_TYPE, "Unsupported node event type: " + value,
                    eventId, value);
        }
    }

    private String safeText(JsonNode root, String fieldName) {
        JsonNode value = root.get(fieldName);
        return value != null && value.isString() ? value.asString() : null;
    }

    private BusinessException failure(NodeError nodeError, String detail, String eventId, String eventType) {
        return new BusinessException(nodeError, new NodeEventErrorContext(eventId, eventType, detail));
    }

    private BusinessException invalidEnvelope(String message, String eventId, String eventType) {
        return failure(NodeError.INVALID_ENVELOPE, message, eventId, eventType);
    }
}
