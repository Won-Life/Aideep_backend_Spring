package com.aideep.domain.node.service;

import com.aideep.domain.node.dto.event.NodeEventEnvelope;

public interface NodeCommandProcessor {
    NodeCommandProcessingResult process(NodeEventEnvelope nodeEventEnvelope);

    default boolean isReady() {
        return true;
    }
}
