package com.aideep.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.node.dto.event.NodeEventType;
import com.aideep.domain.node.exception.NodeEventParseException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NodeEventParserTest {
    private static final String EVENT_ID = "11111111-1111-4111-8111-111111111111";
    private static final String WORKSPACE_ID = "22222222-2222-4222-8222-222222222222";

    private final NodeEventParser nodeEventParser = new NodeEventParser(new ObjectMapper());

    @Test
    void parsesSupportedEnvelopeAndPreservesPayload() {
        var nodeEventEnvelope = nodeEventParser.parse(validEvent("NODE_CREATE_REQUESTED"));

        assertThat(nodeEventEnvelope.version()).isEqualTo(1);
        assertThat(nodeEventEnvelope.eventId()).isEqualTo(UUID.fromString(EVENT_ID));
        assertThat(nodeEventEnvelope.eventType()).isEqualTo(NodeEventType.NODE_CREATE_REQUESTED);
        assertThat(nodeEventEnvelope.occurredAt()).isEqualTo(Instant.parse("2026-09-21T03:30:00Z"));
        assertThat(nodeEventEnvelope.workspaceId()).isEqualTo(UUID.fromString(WORKSPACE_ID));
        assertThat(nodeEventEnvelope.payload().get("title").asString()).isEqualTo("AI meeting summary");
    }

    @Test
    void rejectsUnsupportedVersionAndKeepsAvailableMetadata() {
        String data = validEvent("NODE_CREATE_REQUESTED").replace("\"version\": 1", "\"version\": 2");

        assertThatThrownBy(() -> nodeEventParser.parse(data))
                .isInstanceOfSatisfying(NodeEventParseException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo("UNSUPPORTED_VERSION");
                    assertThat(exception.eventId()).isEqualTo(EVENT_ID);
                    assertThat(exception.eventType()).isEqualTo("NODE_CREATE_REQUESTED");
                });
    }

    @Test
    void rejectsUnsupportedEventType() {
        assertThatThrownBy(() -> nodeEventParser.parse(validEvent("NODE_DELETE_REQUESTED")))
                .isInstanceOfSatisfying(NodeEventParseException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo("UNSUPPORTED_EVENT_TYPE"));
    }

    @Test
    void rejectsMalformedJsonAndInvalidEnvelopeFields() {
        assertThatThrownBy(() -> nodeEventParser.parse("{"))
                .isInstanceOfSatisfying(NodeEventParseException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo("INVALID_JSON"));
        assertThatThrownBy(() -> nodeEventParser.parse(validEvent("NODE_PATCH_REQUESTED")
                        .replace(WORKSPACE_ID, "not-a-uuid")))
                .isInstanceOfSatisfying(NodeEventParseException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo("INVALID_ENVELOPE"));
        assertThatThrownBy(() -> nodeEventParser.parse(validEvent("NODE_PATCH_REQUESTED")
                        .replace(WORKSPACE_ID, "2-2-2-2-2")))
                .isInstanceOfSatisfying(NodeEventParseException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo("INVALID_ENVELOPE"));
        assertThatThrownBy(() -> nodeEventParser.parse(validEvent("NODE_PATCH_REQUESTED")
                        .replace("\"payload\": {\"title\": \"AI meeting summary\"}", "\"payload\": null")))
                .isInstanceOfSatisfying(NodeEventParseException.class,
                        exception -> assertThat(exception.errorCode()).isEqualTo("INVALID_ENVELOPE"));
    }

    private String validEvent(String eventType) {
        return """
                {
                  "version": 1,
                  "eventId": "%s",
                  "eventType": "%s",
                  "occurredAt": "2026-09-21T03:30:00Z",
                  "workspaceId": "%s",
                  "payload": {"title": "AI meeting summary"}
                }
                """.formatted(EVENT_ID, eventType, WORKSPACE_ID);
    }
}
