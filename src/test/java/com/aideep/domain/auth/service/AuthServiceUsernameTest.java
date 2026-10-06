package com.aideep.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.dto.request.ChangeUsernameRequest;
import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.onboarding.service.UserOnboardingProfileService;
import com.aideep.domain.workspace.service.WorkspaceQueryService;
import com.aideep.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** 닉네임 변경 API의 정책을 검증한다. */
class AuthServiceUsernameTest {

    private static final UUID USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private AuthUserRepository authUserRepository;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authUserRepository = mock(AuthUserRepository.class);
        authService = new AuthService(authUserRepository, mock(OAuthService.AuthDatabase.class),
                mock(RedisAuthStore.class), mock(JwtTokenService.class), mock(PasswordEncoder.class),
                mock(UserOnboardingProfileService.class), mock(VerificationMailService.class),
                mock(WorkspaceQueryService.class), mock(AuthProperties.class), new MockEnvironment(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void changesUsernameAndUpdatesTimestamp() {
        AuthUser user = new AuthUser("user@example.com", "hash", NOW.minusSeconds(600));
        user.changeUsername("이전 닉네임", NOW.minusSeconds(600));
        when(authUserRepository.lockById(USER_ID)).thenReturn(Optional.of(user));

        authService.changeUsername(USER_ID, new ChangeUsernameRequest("새 닉네임"));

        assertThat(user.getUsername()).isEqualTo("새 닉네임");
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
    }

    @Test
    void locksRowBeforeChangingUsername() {
        AuthUser user = new AuthUser("user@example.com", "hash", NOW);
        when(authUserRepository.lockById(USER_ID)).thenReturn(Optional.of(user));

        authService.changeUsername(USER_ID, new ChangeUsernameRequest("닉네임"));

        verify(authUserRepository).lockById(USER_ID);
    }

    @Test
    void rejectsUnknownUser() {
        when(authUserRepository.lockById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.changeUsername(USER_ID, new ChangeUsernameRequest("닉네임")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.USER_NOT_FOUND));
    }
}
