package com.aideep.domain.workspace.service;

import com.aideep.domain.workspace.entity.UserWorkspace;
import com.aideep.domain.workspace.entity.WorkspacePermission;
import com.aideep.domain.workspace.repository.UserWorkspaceRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WorkspacePermissionService {

    private final UserWorkspaceRepository userWorkspaceRepository;

    public WorkspacePermissionService(UserWorkspaceRepository userWorkspaceRepository) {
        this.userWorkspaceRepository = userWorkspaceRepository;
    }

    public UserWorkspace requirePermission(UUID userId, UUID workspaceId, WorkspacePermission workspacePermission) {
        UserWorkspace userWorkspace = userWorkspaceRepository
                .findByIdUserIdAndIdWorkspaceIdAndDeletedAtIsNull(userId, workspaceId)
                .orElseThrow(() -> new AccessDeniedException("워크스페이스 접근 권한이 없습니다."));

        if (!userWorkspace.getRole().allows(workspacePermission)) {
            throw new AccessDeniedException("워크스페이스 접근 권한이 없습니다.");
        }
        return userWorkspace;
    }
}
