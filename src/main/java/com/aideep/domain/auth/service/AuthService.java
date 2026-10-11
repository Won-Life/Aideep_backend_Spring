package com.aideep.domain.auth.service;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.dto.Identity;
import com.aideep.domain.auth.dto.request.ChangeUsernameRequest;
import com.aideep.domain.auth.dto.request.LoginRequest;
import com.aideep.domain.auth.dto.request.PasswordRequest;
import com.aideep.domain.auth.dto.request.PasswordResetConfirmRequest;
import com.aideep.domain.auth.dto.request.PasswordResetRequest;
import com.aideep.domain.auth.dto.request.SetOnboard;
import com.aideep.domain.auth.dto.request.SignupRequest;
import com.aideep.domain.auth.dto.response.TokensResponse;
import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.auth.security.CurrentUser;
import com.aideep.domain.onboarding.dto.TermConsent;
import com.aideep.domain.onboarding.service.UserOnboardingProfileService;
import com.aideep.domain.workspace.service.WorkspaceQueryService;
import com.aideep.global.exception.BusinessException;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

@Service
public class AuthService {
    private final AuthUserRepository authUserRepository;
    private final OAuthService.AuthDatabase authDatabase;
    private final RedisAuthStore redisAuthStore;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;
    private final UserOnboardingProfileService userOnboardingProfileService;
    private final VerificationMailService verificationMailService;
    private final WorkspaceQueryService workspaceQueryService;
    private final AuthProperties authProperties;
    private final Environment environment;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(AuthUserRepository authUserRepository, OAuthService.AuthDatabase authDatabase,
                       RedisAuthStore redisAuthStore, JwtTokenService jwtTokenService,
                       PasswordEncoder passwordEncoder, UserOnboardingProfileService userOnboardingProfileService,
                       VerificationMailService verificationMailService, WorkspaceQueryService workspaceQueryService,
                       AuthProperties authProperties, Environment environment, Clock clock) {
        this.authUserRepository = authUserRepository;
        this.authDatabase = authDatabase;
        this.redisAuthStore = redisAuthStore;
        this.jwtTokenService = jwtTokenService;
        this.passwordEncoder = passwordEncoder;
        this.userOnboardingProfileService = userOnboardingProfileService;
        this.verificationMailService = verificationMailService;
        this.workspaceQueryService = workspaceQueryService;
        this.authProperties = authProperties;
        this.environment = environment;
        this.clock = clock;
    }

    public static Identity identity(AuthUser user) {
        return new Identity(user.getEmail(), user.getId().toString());
    }

    public Identity authenticate(LoginRequest body) {
        redisAuthStore.assertNotLocked(body.email());
        var user = authUserRepository.findByEmail(body.email())
                .orElseThrow(() -> {
                    redisAuthStore.recordLoginFailure(body.email());
                    return new BusinessException(AuthError.LOGIN_USER_NOT_FOUND);
                });
        if (user.getPassword() == null || !passwordEncoder.matches(body.password(), user.getPassword())) {
            redisAuthStore.recordLoginFailure(body.email());
            throw new BusinessException(AuthError.PASSWORD_MISMATCH);
        }
        redisAuthStore.clearLoginFailures(body.email());
        return identity(user);
    }

    public TokensResponse login(LoginRequest body) {
        return issue(authenticate(body));
    }

    public TokensResponse issue(Identity user) {
        TokensResponse tokens = jwtTokenService.issue(user);
        redisAuthStore.saveRefresh(user.user_id(), tokens.refreshToken());
        return tokens;
    }

    public TokensResponse refresh(String token) {
        Jwt parsed;
        try {
            parsed = jwtTokenService.decode(token);
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException(AuthError.REFRESH_TOKEN_INVALID);
        }
        Identity user = jwtTokenService.identity(parsed);
        TokensResponse tokens = jwtTokenService.issue(user);
        if (!redisAuthStore.rotateRefresh(user.user_id(), token, tokens.refreshToken()))
            throw new BusinessException(AuthError.REFRESH_TOKEN_EXPIRED);
        return tokens;
    }

    public void logout(CurrentUser currentUser, Jwt token) {
        redisAuthStore.logout(currentUser.userId().toString(), token.getTokenValue(),
                Duration.between(clock.instant(), token.getExpiresAt()).toSeconds());
    }

    public void signup(SignupRequest body) {
        if (authUserRepository.findByEmail(body.email()).isPresent())
            throw new BusinessException(AuthError.EMAIL_ALREADY_EXISTS);
        if (redisAuthStore.get("verified:" + body.email()) == null)
            throw new BusinessException(AuthError.EMAIL_UNVERIFIED);
        try {
            // 닉네임은 가입 시점에 임의로 지정하고, 온보딩에서 사용자가 원하는 값으로 바꿀 수 있게 한다.
            authDatabase.createUser(body.email(), passwordEncoder.encode(body.password()),
                    RandomNickname.generate(),
                    new TermConsent(body.termsOfService(), body.privacyPolicy(), body.marketing()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(AuthError.EMAIL_ALREADY_EXISTS);
        }
        redisAuthStore.delete("verified:" + body.email());
    }

    @Transactional
    public void password(UUID userId, PasswordRequest body) {
        AuthUser user = authUserRepository.lockById(userId)
                .orElseThrow(() -> new BusinessException(AuthError.USER_NOT_FOUND));
        if (user.getPassword() != null) {
            if (body.currentPassword() == null || body.currentPassword().isEmpty())
                throw new BusinessException(AuthError.CURRENT_PASSWORD_REQUIRED);
            if (!passwordEncoder.matches(body.currentPassword(), user.getPassword()))
                throw new BusinessException(AuthError.CURRENT_PASSWORD_MISMATCH);
        }
        user.changePassword(passwordEncoder.encode(body.newPassword()), clock.instant());
    }

    @Transactional
    public void setOnboard(SetOnboard body, UUID userId) {
        AuthUser user = authUserRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(AuthError.USER_NOT_FOUND));
        // 구글 가입자는 가입 시점에 닉네임이 들어가므로, 요청에 없으면 기존 닉네임을 유지한다.
        if (body.userName() != null && !body.userName().isBlank()) {
            user.changeUsername(body.userName(), clock.instant());
        }
        userOnboardingProfileService.save(userId, body.usageProposal(), body.meeting());
    }

    @Transactional
    public void changeUsername(UUID userId, ChangeUsernameRequest body) {
        AuthUser user = authUserRepository.lockById(userId)
                .orElseThrow(() -> new BusinessException(AuthError.USER_NOT_FOUND));
        user.changeUsername(body.username(), clock.instant());
    }

    /** 가입된 이메일에만 1회용 재설정 링크를 보낸다. 링크의 토큰 없이는 재설정할 수 없다. */
    public void requestPasswordReset(PasswordResetRequest body) {
        AuthUser user = authUserRepository.findByEmail(body.email())
                .orElseThrow(() -> new BusinessException(AuthError.USER_NOT_FOUND));
        String token = randomToken();
        redisAuthStore.savePasswordResetToken(token, user.getId().toString());
        verificationMailService.sendPasswordResetLink(user.getEmail(), passwordResetUrl(token));
    }

    @Transactional
    public void confirmPasswordReset(PasswordResetConfirmRequest body) {
        String userId = redisAuthStore.consumePasswordResetToken(body.token());
        if (userId == null) throw new BusinessException(AuthError.PASSWORD_RESET_TOKEN_INVALID);
        AuthUser user = authUserRepository.lockById(UUID.fromString(userId))
                .orElseThrow(() -> new BusinessException(AuthError.PASSWORD_RESET_TOKEN_INVALID));
        user.changePassword(passwordEncoder.encode(body.newPassword()), clock.instant());
        // 비밀번호를 되찾은 사용자가 바로 로그인할 수 있도록 실패 이력과 기존 세션을 정리한다.
        redisAuthStore.clearLoginFailures(user.getEmail());
        redisAuthStore.deleteRefresh(userId);
    }

    /**
     * 계정을 하드 삭제한다. users를 참조하는 OAuth 계정, 워크스페이스 멤버십, 온보딩 데이터는
     * FK의 on delete cascade로 함께 제거된다.
     */
    public void deleteAccount(CurrentUser currentUser, Jwt token) {
        if (workspaceQueryService.existsOwnedWorkspace(currentUser.userId()))
            throw new BusinessException(AuthError.OWNED_WORKSPACE_EXISTS);
        authDatabase.deleteUser(currentUser.userId());
        logout(currentUser, token);
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String passwordResetUrl(String token) {
        if (authProperties.frontendUrl() == null || authProperties.frontendUrl().isBlank())
            throw new IllegalStateException("FRONTEND_URL is required");
        return UriComponentsBuilder
                .fromUriString(authProperties.frontendUrl().replaceAll("/+$", "") + "/password/reset")
                .queryParam("token", token).build().encode().toUriString();
    }
}