package com.aideep.domain.workspace.service;

import com.aideep.domain.workspace.entity.WorkspaceRole;
import com.aideep.domain.workspace.repository.UserWorkspaceRepository;
import com.aideep.domain.workspace.repository.WorkspaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 다른 도메인이 워크스페이스 엔티티나 저장소를 직접 참조하지 않고 존재 여부를 확인하는 경계다.
 */
@Service
@Transactional(readOnly = true)
public class WorkspaceQueryService {

    private final WorkspaceRepository workspaceRepository;
    private final UserWorkspaceRepository userWorkspaceRepository;

    public WorkspaceQueryService(WorkspaceRepository workspaceRepository,
                                 UserWorkspaceRepository userWorkspaceRepository) {
        this.workspaceRepository = workspaceRepository;
        this.userWorkspaceRepository = userWorkspaceRepository;
    }

    public boolean existsActiveWorkspace(UUID workspaceId) {
        return workspaceRepository.existsByIdAndDeletedAtIsNull(workspaceId);
    }

    /** 계정 삭제처럼 소유 워크스페이스가 남아 있으면 안 되는 흐름에서 사용한다. */
    public boolean existsOwnedWorkspace(UUID userId) {
        return userWorkspaceRepository.existsByIdUserIdAndRoleAndDeletedAtIsNull(userId, WorkspaceRole.OWNER);
    }
}
