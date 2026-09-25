package com.aideep.domain.workspace.repository;

import com.aideep.domain.workspace.entity.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WorkspaceRepository extends JpaRepository<Workspace, UUID> {

    boolean existsByIdAndDeletedAtIsNull(UUID id);
}
