package com.aideep.domain.node.service;

import com.aideep.domain.node.entity.Edge;
import com.aideep.domain.node.entity.Node;
import com.aideep.domain.node.exception.NodeError;
import com.aideep.domain.node.repository.EdgeRepository;
import com.aideep.domain.node.repository.NodeRepository;
import com.aideep.global.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 기준 노드에서 엣지를 따라 도달하는 하위 그래프를 조회한다. 워크스페이스 전체 노드는 포함하지 않는다.
 */
@Service
@Transactional(readOnly = true)
public class NodeGraphQueryService {

    private final NodeRepository nodeRepository;
    private final EdgeRepository edgeRepository;

    public NodeGraphQueryService(NodeRepository nodeRepository, EdgeRepository edgeRepository) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
    }

    /**
     * 기준 노드 자신을 포함한다. 기준 노드가 없거나 삭제됐거나 다른 워크스페이스면
     * {@link NodeError#NODE_NOT_FOUND}를 던진다.
     * <p>
     * 탐색은 재귀 CTE 한 번으로 노드 ID를 모은 뒤 노드와 엣지를 각각 한 번씩 조회한다. depth에 비례해 쿼리가 늘어나지 않는다.
     */
    public NodeGraph findDescendantGraph(UUID workspaceId, UUID nodeId) {
        List<UUID> reachableIds = nodeRepository.findReachableNodeIds(workspaceId, nodeId);
        if (reachableIds.isEmpty()) {
            throw new BusinessException(NodeError.NODE_NOT_FOUND,
                    "Node not found in workspace. nodeId=" + nodeId + " workspaceId=" + workspaceId);
        }
        List<Node> nodes = nodeRepository
                .findByIdInAndWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtAsc(reachableIds, workspaceId);
        // source만 걸면 삭제된 노드나 다른 워크스페이스 노드로 향하는 엣지가 남아, nodes에 없는 노드를 가리키는 엣지가 섞인다.
        Set<UUID> reachable = Set.copyOf(reachableIds);
        List<Edge> edges = edgeRepository
                .findByWorkspaceIdAndSourceIdInAndDeletedAtIsNullOrderByCreatedAtAsc(workspaceId, reachableIds)
                .stream()
                .filter(edge -> reachable.contains(edge.getTargetId()))
                .toList();
        return new NodeGraph(nodes, edges);
    }

    public record NodeGraph(List<Node> nodes, List<Edge> edges) {
    }
}
