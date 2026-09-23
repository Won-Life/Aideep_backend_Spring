package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeEventEnvelope;

public class DeferredNodeCommandProcessor implements NodeCommandProcessor {
    @Override
    public NodeCommandProcessingResult process(NodeEventEnvelope nodeEventEnvelope) {
        return NodeCommandProcessingResult.DEFERRED;
    }

    @Override
    public boolean isReady() {
        return false;
    }
}
