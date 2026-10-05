package com.aideep.domain.node.repository;

import com.aideep.domain.node.entity.Node;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;
import java.util.UUID;

public interface NodeRepository extends JpaRepository<Node, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Node> findByIdAndWorkspaceIdAndDeletedAtIsNull(UUID id, UUID workspaceId);
}
