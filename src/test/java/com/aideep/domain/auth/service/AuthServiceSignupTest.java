package com.aideep.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.dto.request.SignupRequest;
import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.onboarding.dto.TermConsent;
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

/** 이메일 회원가입이 임의 닉네임을 지정해 저장하는지 검증한다. */
class AuthServiceSignupTest {

    private static final Instant NOW = Instant.parse("2026-10-09T10:00:00Z");
    private static final SignupRequest BODY =
            new SignupRequest("new@example.com", "raw-password", true, true, true);

    private AuthUserRepository authUserRepository;
    private OAuthService.AuthDatabase authDatabase;
    private RedisAuthStore redisAuthStore;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authUserRepository = mock(AuthUserRepository.class);
        authDatabase = mock(OAuthService.AuthDatabase.class);
        redisAuthStore = mock(RedisAuthStore.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.encode("raw-password")).thenReturn("hashed-password");
        authService = new AuthService(authUserRepository, authDatabase, redisAuthStore,
                mock(JwtTokenService.class), passwordEncoder, mock(UserOnboardingProfileService.class),
                mock(VerificationMailService.class), mock(WorkspaceQueryService.class), mock(AuthProperties.class),
                new MockEnvironment(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private void verifiedEmail() {
        when(authUserRepository.findByEmail(BODY.email())).thenReturn(Optional.empty());
        when(redisAuthStore.get("verified:" + BODY.email())).thenReturn("{}");
    }

    @Test
    void assignsRandomNicknameOnSignup() {
        verifiedEmail();

        authService.signup(BODY);

        ArgumentCaptor<String> username = ArgumentCaptor.forClass(String.class);
        verify(authDatabase).createUser(eq(BODY.email()), eq("hashed-password"), username.capture(),
                eq(new TermConsent(true, true, true)));
        assertThat(username.getValue()).matches("[가-힣]+\\d{4}");
        verify(redisAuthStore).delete("verified:" + BODY.email());
    }

    @Test
    void doesNotCreateUserWhenEmailUnverified() {
        when(authUserRepository.findByEmail(BODY.email())).thenReturn(Optional.empty());
        when(redisAuthStore.get("verified:" + BODY.email())).thenReturn(null);

        assertThatThrownBy(() -> authService.signup(BODY))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.EMAIL_UNVERIFIED));

        verifyNoInteractions(authDatabase);
    }

    @Test
    void doesNotCreateUserWhenEmailAlreadyExists() {
        when(authUserRepository.findByEmail(BODY.email()))
                .thenReturn(Optional.of(new AuthUser(BODY.email(), "hashed-password", NOW)));

        assertThatThrownBy(() -> authService.signup(BODY))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.EMAIL_ALREADY_EXISTS));

        verifyNoInteractions(authDatabase);
    }

    @Test
    void entityKeepsNicknameGivenAtCreation() {
        AuthUser user = new AuthUser(BODY.email(), "hashed-password", "용감한다람쥐4821", NOW);

        assertThat(user.getUsername()).isEqualTo("용감한다람쥐4821");
        assertThat(user.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void generatesDifferentNicknamePerSignup() {
        verifiedEmail();

        authService.signup(BODY);
        authService.signup(BODY);

        ArgumentCaptor<String> username = ArgumentCaptor.forClass(String.class);
        verify(authDatabase, times(2))
                .createUser(anyString(), anyString(), username.capture(), any(TermConsent.class));
        assertThat(username.getAllValues()).allMatch(value -> value.matches("[가-힣]+\\d{4}"));
    }
}
