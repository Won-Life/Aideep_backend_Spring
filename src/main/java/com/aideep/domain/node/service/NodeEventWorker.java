package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeEventEnvelope;
import com.aideep.domain.node.exception.NodeEventParseException;
import com.aideep.domain.node.exception.PermanentNodeEventProcessingException;
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
        } catch (NodeEventParseException exception) {
            return NodeEventWorkResult.permanentFailure(null, exception.eventId(), exception.eventType(),
                    exception.errorCode());
        } catch (PermanentNodeEventProcessingException exception) {
            return NodeEventWorkResult.permanentFailure(nodeEventEnvelope, nodeEventEnvelope.eventId().toString(),
                    nodeEventEnvelope.eventType().name(), exception.errorCode());
        } catch (RuntimeException exception) {
            return NodeEventWorkResult.retryableFailure(nodeEventEnvelope, "PROCESSOR_FAILURE", exception);
        }
    }

    public boolean isReady() {
        return nodeCommandProcessor.isReady();
    }
}
