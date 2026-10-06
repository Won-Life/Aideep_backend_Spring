package com.aideep.domain.auth.service;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.dto.Identity;
import com.aideep.domain.auth.dto.request.ChangeUsernameRequest;
import com.aideep.domain.auth.dto.request.LoginRequest;
import com.aideep.domain.auth.dto.request.PasswordRequest;
import com.aideep.domain.auth.dto.request.SetOnboard;
import com.aideep.domain.auth.dto.request.SignupRequest;
import com.aideep.domain.auth.dto.response.TokensResponse;
import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.auth.security.CurrentUser;
import com.aideep.domain.onboarding.dto.TermConsent;
import com.aideep.domain.onboarding.service.UserOnboardingProfileService;
import com.aideep.global.exception.BusinessException;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Service
public class AuthService {
    private final AuthUserRepository authUserRepository;
    private final OAuthService.AuthDatabase authDatabase;
    private final RedisAuthStore redisAuthStore;
    private final JwtTokenService jwtTokenService;
    private final PasswordEncoder passwordEncoder;
    private final UserOnboardingProfileService userOnboardingProfileService;
    private final AuthProperties authProperties;
    private final Environment environment;
    private final Clock clock;

    public AuthService(AuthUserRepository authUserRepository, OAuthService.AuthDatabase authDatabase,
                       RedisAuthStore redisAuthStore, JwtTokenService jwtTokenService,
                       PasswordEncoder passwordEncoder, UserOnboardingProfileService userOnboardingProfileService,
                       AuthProperties authProperties, Environment environment, Clock clock) {
        this.authUserRepository = authUserRepository;
        this.authDatabase = authDatabase;
        this.redisAuthStore = redisAuthStore;
        this.jwtTokenService = jwtTokenService;
        this.passwordEncoder = passwordEncoder;
        this.userOnboardingProfileService = userOnboardingProfileService;
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
            authDatabase.createUser(body.email(), passwordEncoder.encode(body.password()),
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
}