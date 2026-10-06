package com.aideep.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.dto.request.LoginRequest;
import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.onboarding.service.UserOnboardingProfileService;
import com.aideep.global.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

/** 로그인 실패 5회 누적 시 10분 잠금 정책을 검증한다. */
class AuthServiceLoginLockTest {

    private static final String EMAIL = "user@example.com";
    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private AuthUserRepository authUserRepository;
    private RedisAuthStore redisAuthStore;
    private PasswordEncoder passwordEncoder;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authUserRepository = mock(AuthUserRepository.class);
        redisAuthStore = mock(RedisAuthStore.class);
        passwordEncoder = mock(PasswordEncoder.class);
        authService = new AuthService(authUserRepository, mock(OAuthService.AuthDatabase.class), redisAuthStore,
                mock(JwtTokenService.class), passwordEncoder, mock(UserOnboardingProfileService.class),
                mock(VerificationMailService.class), mock(AuthProperties.class), new MockEnvironment(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AuthUser user() {
        AuthUser user = new AuthUser(EMAIL, "hash", NOW.minusSeconds(600));
        when(authUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void countsFailureWhenPasswordDoesNotMatch() {
        user();
        when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate(new LoginRequest(EMAIL, "wrong")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.PASSWORD_MISMATCH));

        verify(redisAuthStore).recordLoginFailure(EMAIL);
        verify(redisAuthStore, never()).clearLoginFailures(EMAIL);
    }

    @Test
    void countsFailureForUnknownEmailSoAccountsCannotBeEnumerated() {
        when(authUserRepository.findByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.authenticate(new LoginRequest(EMAIL, "whatever")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.LOGIN_USER_NOT_FOUND));

        verify(redisAuthStore).recordLoginFailure(EMAIL);
    }

    @Test
    void rejectsLockedAccountEvenWhenPasswordIsCorrect() {
        doThrow(new BusinessException(AuthError.ACCOUNT_LOCKED)).when(redisAuthStore).assertNotLocked(EMAIL);
        when(passwordEncoder.matches("correct", "hash")).thenReturn(true);

        assertThatThrownBy(() -> authService.authenticate(new LoginRequest(EMAIL, "correct")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.ACCOUNT_LOCKED));

        verify(authUserRepository, never()).findByEmail(EMAIL);
        verify(redisAuthStore, never()).recordLoginFailure(EMAIL);
    }

    @Test
    void clearsFailureHistoryAfterSuccessfulLogin() {
        user();
        when(passwordEncoder.matches("correct", "hash")).thenReturn(true);

        authService.authenticate(new LoginRequest(EMAIL, "correct"));

        verify(redisAuthStore).clearLoginFailures(EMAIL);
        verify(redisAuthStore, never()).recordLoginFailure(EMAIL);
    }

    @Test
    void treatsPasswordlessOAuthOnlyAccountAsFailure() {
        AuthUser user = new AuthUser(EMAIL, null, NOW);
        when(authUserRepository.findByEmail(EMAIL)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> authService.authenticate(new LoginRequest(EMAIL, "anything")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.PASSWORD_MISMATCH));

        verify(redisAuthStore).recordLoginFailure(EMAIL);
    }
}
