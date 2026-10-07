package com.aideep.domain.node.service;

import com.aideep.domain.node.repository.NodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 다른 도메인이 노드 엔티티나 저장소를 직접 참조하지 않고 노드의 존재 여부를 확인하는 경계다.
 */
@Service
@Transactional(readOnly = true)
public class NodeQueryService {

    private final NodeRepository nodeRepository;

    public NodeQueryService(NodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    public boolean existsActiveNode(UUID workspaceId, UUID nodeId) {
        return nodeRepository.existsByIdAndWorkspaceIdAndDeletedAtIsNull(nodeId, workspaceId);
    }
}
