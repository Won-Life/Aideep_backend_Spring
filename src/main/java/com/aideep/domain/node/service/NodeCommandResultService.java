package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeEventType;
import com.aideep.domain.node.entity.NodeCommandResult;
import com.aideep.domain.node.exception.NodeError;
import com.aideep.domain.node.repository.NodeCommandResultRepository;
import com.aideep.domain.node.repository.ProcessedNodeEventRepository;
import com.aideep.global.exception.BusinessException;
import java.time.Clock;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class NodeCommandResultService {
    private final NodeCommandResultRepository nodeCommandResultRepository;
    private final ProcessedNodeEventRepository processedNodeEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public NodeCommandResultService(NodeCommandResultRepository nodeCommandResultRepository,
                                    ProcessedNodeEventRepository processedNodeEventRepository,
                                    ObjectMapper objectMapper, Clock clock) {
        this.nodeCommandResultRepository = nodeCommandResultRepository;
        this.processedNodeEventRepository = processedNodeEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public boolean recordFailure(String data, NodeEventWorkResult nodeEventWorkResult) {
        var command = identity(data, nodeEventWorkResult);
        if (command == null) {
            return true;
        }
        var existing = nodeCommandResultRepository.findByCommandEventId(command.eventId());
        if (existing.isPresent()) {
            existing.get().requestPublication(clock.instant());
            return objectMapper.readTree(existing.get().getData()).path("payload").has("error");
        }
        if (processedNodeEventRepository.existsById(command.eventId())) {
            // Pre-outbox commands have no reconstructible historical result; never invent one from current nodes.
            return false;
        }
        UUID eventId = UUID.randomUUID();
        var now = clock.instant();
        NodeError nodeError = Arrays.stream(NodeError.values())
                .filter(error -> error.getCode().equals(nodeEventWorkResult.errorCode()))
                .findFirst().orElse(NodeError.PROCESSOR_FAILURE);
        String result = objectMapper.writeValueAsString(Map.of(
                "version", 1, "eventId", eventId,
                "eventType", command.eventType().name().replace("_REQUESTED", "_FAILED"),
                "occurredAt", now.toString(), "workspaceId", command.workspaceId(),
                "payload", Map.of("commandEventId", command.eventId(),
                        "commandEventType", command.eventType().name(),
                        "error", Map.of("code", nodeError.getCode(), "message", nodeError.getReason(),
                                "details", safeDetails(nodeError, nodeEventWorkResult.cause())))));
        nodeCommandResultRepository.saveAndFlush(new NodeCommandResult(eventId, command.eventId(), result, now));
        return true;
    }

    private CommandIdentity identity(String data, NodeEventWorkResult nodeEventWorkResult) {
        var envelope = nodeEventWorkResult.nodeEventEnvelope();
        if (envelope != null) {
            return new CommandIdentity(envelope.eventId(), envelope.eventType(), envelope.workspaceId());
        }
        if (data == null) {
            return null;
        }
        try {
            var root = objectMapper.readTree(data);
            return new CommandIdentity(canonicalUuid(root.path("eventId").asString()),
                    NodeEventType.valueOf(root.path("eventType").asString()),
                    canonicalUuid(root.path("workspaceId").asString()));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private UUID canonicalUuid(String value) {
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Non-canonical command identity");
        }
        return uuid;
    }

    private record CommandIdentity(UUID eventId, NodeEventType eventType, UUID workspaceId) {
    }

    private Map<String, Object> safeDetails(NodeError nodeError, Throwable cause) {
        if (nodeError == NodeError.STALE_NODE_VERSION && cause instanceof BusinessException businessException
                && businessException.getData() instanceof Map<?, ?> details
                && details.get("nodeId") instanceof UUID nodeId
                && details.get("expectedVersion") instanceof Integer expectedVersion
                && details.get("currentVersion") instanceof Integer currentVersion) {
            return Map.of("nodeId", nodeId, "expectedVersion", expectedVersion, "currentVersion", currentVersion);
        }
        return Map.of();
    }
}
