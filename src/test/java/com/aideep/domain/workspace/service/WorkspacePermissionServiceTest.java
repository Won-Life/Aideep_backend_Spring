package com.aideep.domain.workspace.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aideep.domain.workspace.entity.UserWorkspace;
import com.aideep.domain.workspace.entity.WorkspacePermission;
import com.aideep.domain.workspace.entity.WorkspaceRole;
import com.aideep.domain.workspace.repository.UserWorkspaceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class WorkspacePermissionServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID WORKSPACE_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant JOINED_AT = Instant.parse("2026-09-21T00:00:00Z");

    @Mock
    private UserWorkspaceRepository userWorkspaceRepository;

    private WorkspacePermissionService workspacePermissionService;

    @BeforeEach
    void setUp() {
        workspacePermissionService = new WorkspacePermissionService(userWorkspaceRepository);
    }

    @Test
    void returnsMembershipWhenRoleAllowsPermission() {
        UserWorkspace userWorkspace = membership(WorkspaceRole.EDITOR);
        when(userWorkspaceRepository.findByIdUserIdAndIdWorkspaceIdAndDeletedAtIsNull(USER_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(userWorkspace));

        UserWorkspace result = workspacePermissionService.requirePermission(
                USER_ID, WORKSPACE_ID, WorkspacePermission.EDIT);

        assertThat(result).isSameAs(userWorkspace);
        verify(userWorkspaceRepository).findByIdUserIdAndIdWorkspaceIdAndDeletedAtIsNull(USER_ID, WORKSPACE_ID);
    }

    @Test
    void deniesPermissionNotGrantedToRole() {
        when(userWorkspaceRepository.findByIdUserIdAndIdWorkspaceIdAndDeletedAtIsNull(USER_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(membership(WorkspaceRole.VIEWER)));

        assertThatThrownBy(() -> workspacePermissionService.requirePermission(
                USER_ID, WORKSPACE_ID, WorkspacePermission.EDIT))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deniesUserWithoutActiveMembership() {
        when(userWorkspaceRepository.findByIdUserIdAndIdWorkspaceIdAndDeletedAtIsNull(USER_ID, WORKSPACE_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> workspacePermissionService.requirePermission(
                USER_ID, WORKSPACE_ID, WorkspacePermission.VIEW))
                .isInstanceOf(AccessDeniedException.class);
    }

    private UserWorkspace membership(WorkspaceRole workspaceRole) {
        return new UserWorkspace(USER_ID, WORKSPACE_ID, workspaceRole, JOINED_AT);
    }
}
