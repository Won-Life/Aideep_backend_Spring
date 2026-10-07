package com.aideep.domain.node.repository;

import com.aideep.domain.node.entity.Edge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EdgeRepository extends JpaRepository<Edge, UUID> {

    List<Edge> findByWorkspaceIdAndSourceIdInAndDeletedAtIsNull(UUID workspaceId, Collection<UUID> sourceIds);

    List<Edge> findByWorkspaceIdAndSourceIdInAndDeletedAtIsNullOrderByCreatedAtAsc(UUID workspaceId,
                                                                                  Collection<UUID> sourceIds);
}
