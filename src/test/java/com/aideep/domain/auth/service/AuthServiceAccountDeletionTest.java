package com.aideep.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.auth.security.CurrentUser;
import com.aideep.domain.onboarding.service.UserOnboardingProfileService;
import com.aideep.domain.workspace.service.WorkspaceQueryService;
import com.aideep.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/** 계정 삭제(탈퇴) 정책을 검증한다. */
class AuthServiceAccountDeletionTest {

    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private OAuthService.AuthDatabase authDatabase;
    private RedisAuthStore redisAuthStore;
    private WorkspaceQueryService workspaceQueryService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authDatabase = mock(OAuthService.AuthDatabase.class);
        redisAuthStore = mock(RedisAuthStore.class);
        workspaceQueryService = mock(WorkspaceQueryService.class);
        authService = new AuthService(mock(AuthUserRepository.class), authDatabase, redisAuthStore,
                mock(JwtTokenService.class), mock(PasswordEncoder.class), mock(UserOnboardingProfileService.class),
                mock(VerificationMailService.class), workspaceQueryService, mock(AuthProperties.class),
                new MockEnvironment(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CurrentUser currentUser() {
        return new CurrentUser(USER_ID, "user@example.com", false);
    }

    private Jwt accessToken() {
        return Jwt.withTokenValue("access-token").header("alg", "HS256")
                .claims(claims -> claims.putAll(Map.of("user_id", USER_ID.toString())))
                .issuedAt(NOW).expiresAt(NOW.plusSeconds(900)).build();
    }

    @Test
    void deletesAccountAndRevokesCurrentSession() {
        when(workspaceQueryService.existsOwnedWorkspace(USER_ID)).thenReturn(false);

        authService.deleteAccount(currentUser(), accessToken());

        verify(authDatabase).deleteUser(USER_ID);
        verify(redisAuthStore).logout(USER_ID.toString(), "access-token", 900);
    }

    @Test
    void rejectsDeletionWhileOwningWorkspace() {
        when(workspaceQueryService.existsOwnedWorkspace(USER_ID)).thenReturn(true);

        assertThatThrownBy(() -> authService.deleteAccount(currentUser(), accessToken()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.OWNED_WORKSPACE_EXISTS));

        verifyNoInteractions(authDatabase);
        verify(redisAuthStore, never()).logout(anyString(), anyString(), anyLong());
    }
}
