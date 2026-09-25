package com.aideep.domain.node.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.aideep.domain.node.exception.NodeError;
import com.aideep.global.exception.BusinessException;
import com.aideep.global.exception.GlobalErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
            throw new BusinessException(NodeError.STALE_NODE_VERSION, "stale node version");
        });
        NodeEventWorker retryableWorker = worker(nodeEventEnvelope -> {
            throw new IllegalStateException("temporary failure");
        });

        assertThat(worker(nodeEventEnvelope -> NodeCommandProcessingResult.PROCESSED).process("{").status())
                .isEqualTo(NodeEventWorkResult.Status.PERMANENT_FAILURE);
        assertThat(permanentWorker.process(VALID_EVENT).errorCode()).isEqualTo("NODE-009");
        assertThat(retryableWorker.process(VALID_EVENT).status())
                .isEqualTo(NodeEventWorkResult.Status.RETRYABLE_FAILURE);
    }

    @ParameterizedTest
    @EnumSource(value = NodeError.class, names = "PROCESSOR_FAILURE", mode = EnumSource.Mode.EXCLUDE)
    void classifiesNodeErrorsAsPermanentAndKeepsEnvelopeMetadata(NodeError nodeError) {
        NodeEventWorker nodeEventWorker = worker(nodeEventEnvelope -> {
            throw new BusinessException(nodeError);
        });
        NodeEventWorkResult result = nodeEventWorker.process(VALID_EVENT);

        assertThat(result.status()).isEqualTo(NodeEventWorkResult.Status.PERMANENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo(nodeError.getCode());
        assertThat(result.eventId()).isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(result.eventType()).isEqualTo("NODE_CREATE_REQUESTED");
    }

    @Test
    void preservesMetadataFromFailedEnvelopeParsing() {
        NodeEventWorkResult result = worker(nodeEventEnvelope -> NodeCommandProcessingResult.PROCESSED)
                .process(VALID_EVENT.replace("\"version\": 1", "\"version\": 2"));

        assertThat(result.status()).isEqualTo(NodeEventWorkResult.Status.PERMANENT_FAILURE);
        assertThat(result.errorCode()).isEqualTo("NODE-003");
        assertThat(result.eventId()).isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(result.eventType()).isEqualTo("NODE_CREATE_REQUESTED");
        assertThat(result.nodeEventEnvelope()).isNull();
    }

    @Test
    void keepsProcessorFailureCodeRetryable() {
        NodeEventWorkResult result = worker(nodeEventEnvelope -> {
            throw new BusinessException(NodeError.PROCESSOR_FAILURE);
        }).process(VALID_EVENT);

        assertThat(result.status()).isEqualTo(NodeEventWorkResult.Status.RETRYABLE_FAILURE);
        assertThat(result.errorCode()).isEqualTo("NODE-011");
    }

    @Test
    void doesNotTreatUnrelatedBusinessErrorsAsPermanentNodeFailures() {
        var businessException = new BusinessException(GlobalErrorCode.INTERNAL_SERVER_ERROR);
        NodeEventWorkResult result = worker(nodeEventEnvelope -> {
            throw businessException;
        }).process(VALID_EVENT);

        assertThat(result.status()).isEqualTo(NodeEventWorkResult.Status.RETRYABLE_FAILURE);
        assertThat(result.errorCode()).isEqualTo("NODE-011");
        assertThat(result.cause()).isSameAs(businessException);
    }

    private NodeEventWorker worker(NodeCommandProcessor nodeCommandProcessor) {
        return new NodeEventWorker(new NodeEventParser(new ObjectMapper()), nodeCommandProcessor);
    }
}
