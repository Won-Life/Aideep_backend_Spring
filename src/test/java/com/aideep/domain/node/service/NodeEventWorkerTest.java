package com.aideep.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aideep.domain.node.exception.PermanentNodeEventProcessingException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NodeEventWorkerTest {
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

    @Test
    void returnsProcessedAndDeferredOutcomes() {
        NodeEventWorker processedWorker = worker(nodeEventEnvelope -> NodeCommandProcessingResult.PROCESSED);
        NodeEventWorker deferredWorker = worker(nodeEventEnvelope -> NodeCommandProcessingResult.DEFERRED);

        assertThat(processedWorker.process(VALID_EVENT).status()).isEqualTo(NodeEventWorkResult.Status.PROCESSED);
        assertThat(deferredWorker.process(VALID_EVENT).status()).isEqualTo(NodeEventWorkResult.Status.DEFERRED);
    }

    @Test
    void classifiesContractAndProcessorFailures() {
        NodeEventWorker permanentWorker = worker(nodeEventEnvelope -> {
            throw new PermanentNodeEventProcessingException("STALE_NODE_VERSION", "stale node version");
        });
        NodeEventWorker retryableWorker = worker(nodeEventEnvelope -> {
            throw new IllegalStateException("temporary failure");
        });

        assertThat(worker(nodeEventEnvelope -> NodeCommandProcessingResult.PROCESSED).process("{").status())
                .isEqualTo(NodeEventWorkResult.Status.PERMANENT_FAILURE);
        assertThat(permanentWorker.process(VALID_EVENT).errorCode()).isEqualTo("STALE_NODE_VERSION");
        assertThat(retryableWorker.process(VALID_EVENT).status())
                .isEqualTo(NodeEventWorkResult.Status.RETRYABLE_FAILURE);
    }

    private NodeEventWorker worker(NodeCommandProcessor nodeCommandProcessor) {
        return new NodeEventWorker(new NodeEventParser(new ObjectMapper()), nodeCommandProcessor);
    }
}
