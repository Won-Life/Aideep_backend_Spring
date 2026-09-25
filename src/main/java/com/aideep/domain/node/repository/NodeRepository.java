package com.aideep.domain.node.repository;

import com.aideep.domain.node.entity.Node;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface NodeRepository extends JpaRepository<Node, UUID> {

    Optional<Node> findByIdAndWorkspaceIdAndDeletedAtIsNull(UUID id, UUID workspaceId);
}
