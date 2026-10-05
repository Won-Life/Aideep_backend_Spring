package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeEventEnvelope;
import com.aideep.domain.node.dto.event.TerminalNodeCommandFailure;
import com.aideep.domain.node.dto.event.NodeEventErrorContext;
import com.aideep.domain.node.exception.NodeError;
import com.aideep.global.exception.BusinessException;
import org.springframework.stereotype.Service;

@Service
public class NodeEventWorker {
    private final NodeEventParser nodeEventParser;
    private final NodeCommandProcessor nodeCommandProcessor;

    public NodeEventWorker(NodeEventParser nodeEventParser, NodeCommandProcessor nodeCommandProcessor) {
        this.nodeEventParser = nodeEventParser;
        this.nodeCommandProcessor = nodeCommandProcessor;
    }

    public NodeEventWorkResult process(String data) {
        NodeEventEnvelope nodeEventEnvelope = null;
        try {
            nodeEventEnvelope = nodeEventParser.parse(data);
            NodeCommandProcessingResult result = nodeCommandProcessor.process(nodeEventEnvelope);
            if (result == NodeCommandProcessingResult.PROCESSED) {
                return NodeEventWorkResult.processed(nodeEventEnvelope);
            }
            if (result == NodeCommandProcessingResult.DEFERRED) {
                return NodeEventWorkResult.deferred(nodeEventEnvelope);
            }
            throw new IllegalStateException("Node command processor returned no result");
        } catch (BusinessException exception) {
            if (!(exception.getErrorCode() instanceof NodeError nodeError)
                    || (nodeError == NodeError.PROCESSOR_FAILURE
                    && !(exception.getData() instanceof TerminalNodeCommandFailure))) {
                return NodeEventWorkResult.retryableFailure(nodeEventEnvelope, NodeError.PROCESSOR_FAILURE.getCode(), exception);
            }
            if (nodeEventEnvelope != null) {
                return NodeEventWorkResult.permanentFailure(nodeEventEnvelope, nodeEventEnvelope.eventId().toString(),
                        nodeEventEnvelope.eventType().name(), exception.getErrorCode().getCode(), exception);
            }
            NodeEventErrorContext nodeEventErrorContext = exception.getData() instanceof NodeEventErrorContext context
                    ? context : new NodeEventErrorContext(null, null, null);
            return NodeEventWorkResult.permanentFailure(null, nodeEventErrorContext.eventId(),
                    nodeEventErrorContext.eventType(), exception.getErrorCode().getCode(), exception);
        } catch (RuntimeException exception) {
            return NodeEventWorkResult.retryableFailure(nodeEventEnvelope, NodeError.PROCESSOR_FAILURE.getCode(), exception);
        }
    }

    public boolean isReady() {
        return nodeCommandProcessor.isReady();
    }
}
