package com.aideep.domain.workspace.repository;

import com.aideep.domain.workspace.entity.UserWorkspace;
import com.aideep.domain.workspace.entity.UserWorkspaceId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface UserWorkspaceRepository extends JpaRepository<UserWorkspace, UserWorkspaceId> {

    Optional<UserWorkspace> findByIdUserIdAndIdWorkspaceIdAndDeletedAtIsNull(UUID userId, UUID workspaceId);
}
