package com.aideep.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;

import com.aideep.domain.node.dto.event.NodeEventEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.ObjectMapper;

class PersistentNodeCommandProcessorTest {

    private static final String VALID_EVENT = """
            {
              "version": 1,
              "eventId": "11111111-1111-4111-8111-111111111111",
              "eventType": "NODE_CREATE_REQUESTED",
              "occurredAt": "2026-09-21T03:30:00Z",
              "workspaceId": "22222222-2222-4222-8222-222222222222",
              "payload": {}
            }
            """;

    private final NodeCommandService nodeCommandService = mock(NodeCommandService.class);
    private final PersistentNodeCommandProcessor persistentNodeCommandProcessor =
            new PersistentNodeCommandProcessor(nodeCommandService);
    private final NodeEventEnvelope nodeEventEnvelope = new NodeEventParser(new ObjectMapper()).parse(VALID_EVENT);

    @Test
    void reportsProcessedAndReady() {
        assertThat(persistentNodeCommandProcessor.process(nodeEventEnvelope))
                .isEqualTo(NodeCommandProcessingResult.PROCESSED);
        assertThat(persistentNodeCommandProcessor.isReady()).isTrue();
        then(nodeCommandService).should().apply(nodeEventEnvelope);
    }

    @Test
    void treatsUniqueConflictOfAlreadyProcessedEventAsSuccess() {
        willThrow(new DataIntegrityViolationException("duplicate key")).given(nodeCommandService)
                .apply(any(NodeEventEnvelope.class));
        given(nodeCommandService.isProcessed(nodeEventEnvelope.eventId())).willReturn(true);

        assertThat(persistentNodeCommandProcessor.process(nodeEventEnvelope))
                .isEqualTo(NodeCommandProcessingResult.PROCESSED);
    }

    @Test
    void rethrowsUniqueConflictThatIsNotAProcessedEvent() {
        willThrow(new DataIntegrityViolationException("foreign key violation")).given(nodeCommandService)
                .apply(any(NodeEventEnvelope.class));
        given(nodeCommandService.isProcessed(nodeEventEnvelope.eventId())).willReturn(false);

        assertThatThrownBy(() -> persistentNodeCommandProcessor.process(nodeEventEnvelope))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
