package com.aideep.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.node.dto.event.NodeCreateCommand;
import com.aideep.domain.node.dto.event.NodeEventEnvelope;
import com.aideep.domain.node.dto.event.NodePatchCommand;
import com.aideep.domain.node.entity.NodeType;
import com.aideep.global.exception.BusinessException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NodeCommandPayloadParserTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String NODE_ID = "55555555-5555-4555-8555-555555555555";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final NodeEventParser nodeEventParser = new NodeEventParser(objectMapper);
    private final NodeCommandPayloadParser nodeCommandPayloadParser = new NodeCommandPayloadParser();

    @Test
    void parsesCreatePayloadWithNestedPositionAndData() {
        NodeEventEnvelope nodeEventEnvelope = envelope("NODE_CREATE_REQUESTED", """
                {
                  "title": "AI 회의 요약",
                  "nodeType": "DATA",
                  "position": {"x": 100, "y": 200},
                  "data": {
                    "dataType": "MARKDOWN",
                    "markdownBody": "# 회의 요약",
                    "jsonBody": "{}",
                    "color": "#ffffff",
                    "textColor": "#000000"
                  }
                }
                """);

        NodeCreateCommand nodeCreateCommand = nodeCommandPayloadParser.parseCreate(nodeEventEnvelope);

        assertThat(nodeCreateCommand.title()).isEqualTo("AI 회의 요약");
        assertThat(nodeCreateCommand.nodeType()).isEqualTo(NodeType.DATA);
        assertThat(nodeCreateCommand.position().x()).isEqualTo(100);
        assertThat(nodeCreateCommand.position().y()).isEqualTo(200);
        assertThat(nodeCreateCommand.data().get("markdownBody").asString()).isEqualTo("# 회의 요약");
    }

    @Test
    void rejectsCreatePayloadWithMissingOrInvalidFields() {
        assertThatThrownBy(() -> nodeCommandPayloadParser.parseCreate(
                envelope("NODE_CREATE_REQUESTED", "{\"nodeType\":\"DATA\",\"position\":{\"x\":1,\"y\":2},\"data\":{}}")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode().getCode())
                .isEqualTo("NODE-005");
        assertThatThrownBy(() -> nodeCommandPayloadParser.parseCreate(envelope("NODE_CREATE_REQUESTED",
                "{\"title\":\"t\",\"nodeType\":\"DATA\",\"position\":{\"x\":1},\"data\":{}}")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> nodeCommandPayloadParser.parseCreate(envelope("NODE_CREATE_REQUESTED",
                "{\"title\":\"t\",\"nodeType\":\"UNKNOWN\",\"position\":{\"x\":1,\"y\":2},\"data\":{}}")))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode().getCode())
                .isEqualTo("NODE-006");
        assertThatThrownBy(() -> nodeCommandPayloadParser.parseCreate(envelope("NODE_CREATE_REQUESTED",
                "{\"title\":\"t\",\"nodeType\":\"DATA\",\"position\":{\"x\":1,\"y\":2},\"data\":\"text\"}")))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void parsesPatchPayloadKeepingOmittedFieldsNull() {
        NodeEventEnvelope nodeEventEnvelope = envelope("NODE_PATCH_REQUESTED", """
                {
                  "nodeId": "%s",
                  "expectedVersion": 7,
                  "patch": {
                    "data": {"markdownBody": "AI가 수정한 내용"}
                  }
                }
                """.formatted(NODE_ID));

        NodePatchCommand nodePatchCommand = nodeCommandPayloadParser.parsePatch(nodeEventEnvelope);

        assertThat(nodePatchCommand.nodeId()).isEqualTo(UUID.fromString(NODE_ID));
        assertThat(nodePatchCommand.expectedVersion()).isEqualTo(7);
        assertThat(nodePatchCommand.title()).isNull();
        assertThat(nodePatchCommand.nodeType()).isNull();
        assertThat(nodePatchCommand.data().get("markdownBody").asString()).isEqualTo("AI가 수정한 내용");
    }

    @Test
    void rejectsPatchPayloadWithoutChangesOrWithInvalidVersion() {
        assertThatThrownBy(() -> nodeCommandPayloadParser.parsePatch(envelope("NODE_PATCH_REQUESTED",
                "{\"nodeId\":\"%s\",\"expectedVersion\":1,\"patch\":{}}".formatted(NODE_ID))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> nodeCommandPayloadParser.parsePatch(envelope("NODE_PATCH_REQUESTED",
                "{\"nodeId\":\"%s\",\"expectedVersion\":0,\"patch\":{\"title\":\"t\"}}".formatted(NODE_ID))))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> nodeCommandPayloadParser.parsePatch(envelope("NODE_PATCH_REQUESTED",
                "{\"nodeId\":\"not-a-uuid\",\"expectedVersion\":1,\"patch\":{\"title\":\"t\"}}")))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> nodeCommandPayloadParser.parsePatch(envelope("NODE_PATCH_REQUESTED",
                "{\"nodeId\":\"%s\",\"expectedVersion\":1,\"patch\":{\"title\":null}}".formatted(NODE_ID))))
                .isInstanceOf(BusinessException.class);
    }

    private NodeEventEnvelope envelope(String eventType, String payload) {
        return nodeEventParser.parse("""
                {
                  "version": 1,
                  "eventId": "%s",
                  "eventType": "%s",
                  "occurredAt": "2026-09-21T03:30:00Z",
                  "workspaceId": "%s",
                  "payload": %s
                }
                """.formatted(EVENT_ID, eventType, WORKSPACE_ID, payload));
    }
}
