package com.aideep.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.dto.request.PasswordResetConfirmRequest;
import com.aideep.domain.auth.dto.request.PasswordResetRequest;
import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.onboarding.service.UserOnboardingProfileService;
import com.aideep.domain.workspace.service.WorkspaceQueryService;
import com.aideep.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

/** 비밀번호 찾기(링크 발송)와 링크를 통한 재설정 정책을 검증한다. */
class AuthServicePasswordResetTest {

    private static final String EMAIL = "user@example.com";
    private static final UUID USER_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private AuthUserRepository authUserRepository;
    private RedisAuthStore redisAuthStore;
    private VerificationMailService verificationMailService;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authUserRepository = mock(AuthUserRepository.class);
        redisAuthStore = mock(RedisAuthStore.class);
        verificationMailService = mock(VerificationMailService.class);
        passwordEncoder = mock(PasswordEncoder.class);
        AuthProperties authProperties = new AuthProperties(null, "https://app.example.com/", null, null, null, null,
                null, null, null, null, null, null, null);
        authService = new AuthService(authUserRepository, mock(OAuthService.AuthDatabase.class), redisAuthStore,
                mock(JwtTokenService.class), passwordEncoder, mock(UserOnboardingProfileService.class),
                verificationMailService, mock(WorkspaceQueryService.class), authProperties, new MockEnvironment(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AuthUser user() {
        AuthUser user = new AuthUser(EMAIL, "old-hash", NOW.minusSeconds(600));
        when(authUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(authUserRepository.lockById(any(UUID.class))).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void sendsResetLinkContainingTheSavedToken() {
        user();

        authService.requestPasswordReset(new PasswordResetRequest(EMAIL));

        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisAuthStore).savePasswordResetToken(tokenCaptor.capture(), anyString());
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(verificationMailService).sendPasswordResetLink(eq(EMAIL), urlCaptor.capture());
        assertThat(urlCaptor.getValue())
                .isEqualTo("https://app.example.com/password/reset?token=" + tokenCaptor.getValue());
    }

    @Test
    void rejectsUnknownEmailWithoutSendingMail() {
        when(authUserRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.requestPasswordReset(new PasswordResetRequest(EMAIL)))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.USER_NOT_FOUND));

        verifyNoInteractions(verificationMailService);
        verify(redisAuthStore, never()).savePasswordResetToken(anyString(), anyString());
    }

    @Test
    void resetsPasswordAndClearsSessionsWhenTokenIsValid() {
        AuthUser user = user();
        when(redisAuthStore.consumePasswordResetToken("token")).thenReturn(USER_ID.toString());
        when(passwordEncoder.encode("new-password")).thenReturn("new-hash");

        authService.confirmPasswordReset(new PasswordResetConfirmRequest("token", "new-password"));

        assertThat(user.getPassword()).isEqualTo("new-hash");
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        verify(redisAuthStore).clearLoginFailures(EMAIL);
        verify(redisAuthStore).deleteRefresh(USER_ID.toString());
    }

    @Test
    void rejectsUsedOrExpiredToken() {
        when(redisAuthStore.consumePasswordResetToken("token")).thenReturn(null);

        assertThatThrownBy(() ->
                authService.confirmPasswordReset(new PasswordResetConfirmRequest("token", "new-password")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.PASSWORD_RESET_TOKEN_INVALID));

        verify(authUserRepository, never()).lockById(any(UUID.class));
    }

    @Test
    void rejectsTokenWhoseUserWasDeleted() {
        when(redisAuthStore.consumePasswordResetToken("token")).thenReturn(USER_ID.toString());
        when(authUserRepository.lockById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                authService.confirmPasswordReset(new PasswordResetConfirmRequest("token", "new-password")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.PASSWORD_RESET_TOKEN_INVALID));
    }
}
