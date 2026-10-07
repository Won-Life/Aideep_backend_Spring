package com.aideep.domain.node.repository;

import com.aideep.domain.node.entity.Node;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NodeRepository extends JpaRepository<Node, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Node> findByIdAndWorkspaceIdAndDeletedAtIsNull(UUID id, UUID workspaceId);

    boolean existsByIdAndWorkspaceIdAndDeletedAtIsNull(UUID id, UUID workspaceId);

    List<Node> findByIdInAndWorkspaceIdAndDeletedAtIsNullOrderByCreatedAtAsc(Collection<UUID> ids, UUID workspaceId);

    /**
     * 기준 노드와, 엣지를 따라 도달하는 하위 노드의 ID를 한 번의 재귀 CTE로 모은다.
     * <p>
     * depth마다 조회를 반복하지 않으며, {@code union}이 이미 방문한 노드를 다시 확장하지 않으므로 순환 엣지에서도 종료한다. 삭제된 노드와
     * 엣지는 따라가지 않고, 모든 노드·엣지를 요청 워크스페이스로 제한한다.
     */
    @Query(value = """
            with recursive reachable as (select root.node_id
                                         from nodes root
                                         where root.node_id = :nodeId
                                           and root.workspace_id = :workspaceId
                                           and root.deleted_at is null
                                         union
                                         select target.node_id
                                         from reachable
                                                  join edges edge
                                                       on edge.source_id = reachable.node_id
                                                           and edge.workspace_id = :workspaceId
                                                           and edge.deleted_at is null
                                                  join nodes target
                                                       on target.node_id = edge.target_id
                                                           and target.workspace_id = :workspaceId
                                                           and target.deleted_at is null)
            select node_id from reachable
            """, nativeQuery = true)
    List<UUID> findReachableNodeIds(@Param("workspaceId") UUID workspaceId, @Param("nodeId") UUID nodeId);
}
