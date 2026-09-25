package com.aideep.domain.node.support;

import com.aideep.domain.node.dto.event.NodeEventEnvelope;
import com.aideep.domain.node.service.NodeCommandProcessingResult;
import com.aideep.domain.node.service.NodeCommandProcessor;

/**
 * 준비되지 않은 processor를 모사해 정상 이벤트가 Pending에 보존되는지 검증하는 테스트 대역이다.
 */
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
