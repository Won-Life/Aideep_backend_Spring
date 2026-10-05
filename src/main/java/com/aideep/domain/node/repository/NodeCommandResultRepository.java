package com.aideep.domain.node.repository;

import com.aideep.domain.node.entity.NodeCommandResult;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface NodeCommandResultRepository extends JpaRepository<NodeCommandResult, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<NodeCommandResult> findByCommandEventId(UUID commandEventId);

    @Query(value = "select * from node_command_results where published_at is null "
            + "order by created_at, id limit 1 for update skip locked", nativeQuery = true)
    Optional<NodeCommandResult> lockNextUnpublished();
}
