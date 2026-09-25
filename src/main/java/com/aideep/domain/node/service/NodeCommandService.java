package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeCreateCommand;
import com.aideep.domain.node.dto.event.NodeEventEnvelope;
import com.aideep.domain.node.dto.event.NodePatchCommand;
import com.aideep.domain.node.dto.event.NodePosition;
import com.aideep.domain.node.dto.event.NodeRealtimeEvent;
import com.aideep.domain.node.dto.event.NodeWorkspaceEvent;
import com.aideep.domain.node.entity.Node;
import com.aideep.domain.node.entity.ProcessedNodeEvent;
import com.aideep.domain.node.exception.NodeError;
import com.aideep.domain.node.repository.NodeRepository;
import com.aideep.domain.node.repository.ProcessedNodeEventRepository;
import com.aideep.domain.workspace.service.WorkspaceQueryService;
import com.aideep.global.exception.BusinessException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 노드 이벤트를 하나의 DB 트랜잭션에서 반영하고 처리 이력을 기록한다.
 */
@Service
public class NodeCommandService {

    private final NodeRepository nodeRepository;
    private final ProcessedNodeEventRepository processedNodeEventRepository;
    private final NodeCommandPayloadParser nodeCommandPayloadParser;
    private final WorkspaceQueryService workspaceQueryService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ApplicationEventPublisher applicationEventPublisher;

    public NodeCommandService(NodeRepository nodeRepository,
                              ProcessedNodeEventRepository processedNodeEventRepository,
                              NodeCommandPayloadParser nodeCommandPayloadParser,
                              WorkspaceQueryService workspaceQueryService,
                              ObjectMapper objectMapper,
                              Clock clock,
                              ApplicationEventPublisher applicationEventPublisher) {
        this.nodeRepository = nodeRepository;
        this.processedNodeEventRepository = processedNodeEventRepository;
        this.nodeCommandPayloadParser = nodeCommandPayloadParser;
        this.workspaceQueryService = workspaceQueryService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public boolean isProcessed(UUID eventId) {
        return processedNodeEventRepository.existsById(eventId);
    }

    @Transactional
    public void apply(NodeEventEnvelope nodeEventEnvelope) {
        if (processedNodeEventRepository.existsById(nodeEventEnvelope.eventId())) {
            return;
        }
        if (!workspaceQueryService.existsActiveWorkspace(nodeEventEnvelope.workspaceId())) {
            throw new BusinessException(NodeError.WORKSPACE_NOT_FOUND,
                    "Workspace not found: " + nodeEventEnvelope.workspaceId());
        }

        Instant now = clock.instant();
        NodeWorkspaceEvent nodeWorkspaceEvent = switch (nodeEventEnvelope.eventType()) {
            case NODE_CREATE_REQUESTED -> createNode(nodeEventEnvelope, now);
            case NODE_PATCH_REQUESTED -> patchNode(nodeEventEnvelope, now);
        };
        processedNodeEventRepository.save(new ProcessedNodeEvent(nodeEventEnvelope.eventId(),
                nodeEventEnvelope.eventType().name(), nodeEventEnvelope.workspaceId(),
                nodeEventEnvelope.occurredAt(), now));
        processedNodeEventRepository.flush();
        applicationEventPublisher.publishEvent(new NodeRealtimeEvent(nodeEventEnvelope.eventId(), nodeWorkspaceEvent));
    }

    private NodeWorkspaceEvent createNode(NodeEventEnvelope nodeEventEnvelope, Instant now) {
        NodeCreateCommand nodeCreateCommand = nodeCommandPayloadParser.parseCreate(nodeEventEnvelope);
        Node node = nodeRepository.save(Node.create(nodeEventEnvelope.workspaceId(), nodeCreateCommand.title(),
                nodeCreateCommand.nodeType(), nodeCreateCommand.position().x(), nodeCreateCommand.position().y(),
                writeJson(nodeCreateCommand.data()), now));
        return new NodeWorkspaceEvent.Created("NODE_CREATE", node.getWorkspaceId(), "system:ai",
                new NodeWorkspaceEvent.NodeSnapshot(node.getId(), node.getTitle(), node.getNodeType().name(),
                        new NodePosition(node.getPositionX(), node.getPositionY()), readObject(node.getContent()),
                        node.getCreatedAt()));
    }

    private NodeWorkspaceEvent patchNode(NodeEventEnvelope nodeEventEnvelope, Instant now) {
        NodePatchCommand nodePatchCommand = nodeCommandPayloadParser.parsePatch(nodeEventEnvelope);
        Node node = nodeRepository
                .findByIdAndWorkspaceIdAndDeletedAtIsNull(nodePatchCommand.nodeId(),
                        nodeEventEnvelope.workspaceId())
                .orElseThrow(() -> new BusinessException(NodeError.NODE_NOT_FOUND,
                        "Node not found in workspace. nodeId=" + nodePatchCommand.nodeId()
                                + " workspaceId=" + nodeEventEnvelope.workspaceId()));
        node.applyPatch(nodePatchCommand.title(), nodePatchCommand.nodeType(),
                mergeContent(node.getContent(), nodePatchCommand.data()),
                nodePatchCommand.expectedVersion(), now);
        return new NodeWorkspaceEvent.Updated("NODE_UPDATE", node.getWorkspaceId(), "system:ai", node.getId(),
                new NodeWorkspaceEvent.Patch(nodePatchCommand.title(),
                        nodePatchCommand.nodeType() == null ? null : node.getNodeType().name(),
                        nodePatchCommand.data() == null ? null : readObject(node.getContent())));
    }

    private String mergeContent(String currentContent, ObjectNode patchData) {
        if (patchData == null) {
            return null;
        }
        ObjectNode merged = readObject(currentContent);
        merged.setAll(patchData);
        return writeJson(merged);
    }

    private ObjectNode readObject(String content) {
        if (content == null || content.isBlank()) {
            return objectMapper.createObjectNode();
        }
        JsonNode jsonNode = objectMapper.readTree(content);
        return jsonNode.isObject() ? (ObjectNode) jsonNode : objectMapper.createObjectNode();
    }

    private String writeJson(ObjectNode objectNode) {
        return objectMapper.writeValueAsString(objectNode);
    }
}
