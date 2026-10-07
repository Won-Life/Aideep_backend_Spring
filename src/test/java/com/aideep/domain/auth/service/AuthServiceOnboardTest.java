package com.aideep.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.dto.request.SetOnboard;
import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.onboarding.entity.MeetingPlatform;
import com.aideep.domain.onboarding.entity.UsagePurpose;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 온보딩 저장 흐름(닉네임 지정 + 설문 저장)의 정책을 검증한다. */
class AuthServiceOnboardTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    private AuthUserRepository authUserRepository;
    private UserOnboardingProfileService userOnboardingProfileService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        authUserRepository = mock(AuthUserRepository.class);
        userOnboardingProfileService = mock(UserOnboardingProfileService.class);
        authService = new AuthService(authUserRepository, mock(OAuthService.AuthDatabase.class),
                mock(RedisAuthStore.class), mock(JwtTokenService.class), mock(PasswordEncoder.class),
                userOnboardingProfileService, mock(VerificationMailService.class),
                mock(WorkspaceQueryService.class), mock(AuthProperties.class), new MockEnvironment(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AuthUser user(String username) {
        AuthUser user = new AuthUser("user@example.com", null, NOW.minusSeconds(600));
        if (username != null) {
            user.changeUsername(username, NOW.minusSeconds(600));
        }
        when(authUserRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void savesNicknameAndSurveyAnswers() {
        AuthUser user = user(null);
        SetOnboard body = new SetOnboard("새 닉네임", UsagePurpose.TEAM_PROJECT,
                List.of(MeetingPlatform.ZOOM, MeetingPlatform.GOOGLE_MEET));

        authService.setOnboard(body, USER_ID);

        assertThat(user.getUsername()).isEqualTo("새 닉네임");
        assertThat(user.getUpdatedAt()).isEqualTo(NOW);
        verify(userOnboardingProfileService).save(USER_ID, UsagePurpose.TEAM_PROJECT,
                List.of(MeetingPlatform.ZOOM, MeetingPlatform.GOOGLE_MEET));
    }

    @Test
    void keepsGoogleNicknameWhenRequestHasNone() {
        AuthUser user = user("Google User");

        authService.setOnboard(new SetOnboard(null, UsagePurpose.SIDE_PROJECT, List.of()), USER_ID);

        assertThat(user.getUsername()).as("요청에 닉네임이 없으면 가입 시 저장한 구글 이름을 유지한다")
                .isEqualTo("Google User");
        verify(userOnboardingProfileService).save(USER_ID, UsagePurpose.SIDE_PROJECT, List.of());
    }

    @Test
    void treatsBlankNicknameAsNotProvided() {
        AuthUser user = user("Google User");

        authService.setOnboard(new SetOnboard("   ", null, null), USER_ID);

        assertThat(user.getUsername()).isEqualTo("Google User");
        verify(userOnboardingProfileService).save(USER_ID, null, null);
    }

    @Test
    void rejectsUnknownUserBeforeSavingProfile() {
        when(authUserRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.setOnboard(
                new SetOnboard("닉네임", UsagePurpose.OTHER, List.of()), USER_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.USER_NOT_FOUND));

        verifyNoInteractions(userOnboardingProfileService);
        verify(authUserRepository, never()).save(any(AuthUser.class));
    }

    @Test
    void savesProfileOncePerRequest() {
        user("기존");

        authService.setOnboard(new SetOnboard(null, UsagePurpose.STUDY_CLUB, List.of(MeetingPlatform.OFFLINE)),
                USER_ID);

        verify(userOnboardingProfileService).save(eq(USER_ID), eq(UsagePurpose.STUDY_CLUB), any());
    }
}
