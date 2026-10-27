package com.aideep.domain.node.service;

import com.aideep.domain.node.entity.Node;
import com.aideep.domain.node.repository.NodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 다른 도메인이 노드 엔티티나 저장소를 직접 참조하지 않고 노드의 수정 잠금을 다루는 경계다.
 * <p>
 * 잠금은 사용자 직접 수정만 막기 위한 표시다. AI 서버가 보내는 노드 명령은 잠금 여부와 무관하게 반영하므로 이 서비스는 명령 처리 경로에 끼어들지 않는다.
 */
@Service
@Transactional
public class NodeLockService {

    private final NodeRepository nodeRepository;
    private final NodeGraphQueryService nodeGraphQueryService;

    public NodeLockService(NodeRepository nodeRepository, NodeGraphQueryService nodeGraphQueryService) {
        this.nodeRepository = nodeRepository;
        this.nodeGraphQueryService = nodeGraphQueryService;
    }

    /**
     * 기준 노드와 엣지를 따라 도달하는 하위 노드 전체를 회의 소유로 잠근다. 새로 잠근 노드 수를 반환한다.
     * <p>
     * 잠금 시점의 하위 그래프를 한 번 훑는다. 회의 중 AI가 새로 만든 노드는 잠기지 않는다.
     */
    public int lockDescendants(UUID workspaceId, UUID nodeId, UUID meetingId, Instant now) {
        List<Node> nodes = nodeGraphQueryService.findDescendantGraph(workspaceId, nodeId).nodes();
        List<Node> locked = nodes.stream()
                .filter(node -> node.lockForMeeting(meetingId, now))
                .toList();
        nodeRepository.saveAll(locked);
        return locked.size();
    }

    /**
     * 회의가 잠근 노드를 모두 해제한다. 해제한 노드 수를 반환한다.
     * <p>
     * 회의 상태를 반영하는 트랜잭션에서 함께 호출해야 한다. 별도 트랜잭션으로 미루면 상태만 종결되고 노드가 영구히 잠긴 채 남을 수 있다.
     */
    public int unlockByMeeting(UUID meetingId, Instant now) {
        List<Node> nodes = nodeRepository.findByLockedByMeetingIdAndDeletedAtIsNull(meetingId);
        List<Node> unlocked = nodes.stream()
                .filter(node -> node.unlockForMeeting(meetingId, now))
                .toList();
        nodeRepository.saveAll(unlocked);
        return unlocked.size();
    }
}
