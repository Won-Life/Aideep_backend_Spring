package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeEventEnvelope;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Redis에서 받은 노드 명령을 실제 DB에 반영하는 processor다.
 */
@Service
public class PersistentNodeCommandProcessor implements NodeCommandProcessor {

    private final NodeCommandService nodeCommandService;

    public PersistentNodeCommandProcessor(NodeCommandService nodeCommandService) {
        this.nodeCommandService = nodeCommandService;
    }

    @Override
    public NodeCommandProcessingResult process(NodeEventEnvelope nodeEventEnvelope) {
        try {
            nodeCommandService.apply(nodeEventEnvelope);
        } catch (DataIntegrityViolationException exception) {
            if (!nodeCommandService.isProcessed(nodeEventEnvelope.eventId())) {
                throw exception;
            }
        }
        return NodeCommandProcessingResult.PROCESSED;
    }

    @Override
    public boolean isReady() {
        return true;
    }
}
