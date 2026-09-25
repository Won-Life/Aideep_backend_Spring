package com.aideep.domain.workspace.service;

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

    public WorkspaceQueryService(WorkspaceRepository workspaceRepository) {
        this.workspaceRepository = workspaceRepository;
    }

    public boolean existsActiveWorkspace(UUID workspaceId) {
        return workspaceRepository.existsByIdAndDeletedAtIsNull(workspaceId);
    }
}
