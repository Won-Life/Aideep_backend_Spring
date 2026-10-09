package com.aideep.domain.auth.service;

import com.aideep.domain.auth.dto.GoogleProfile;
import com.aideep.domain.auth.dto.OAuthResult;
import com.aideep.domain.auth.dto.OAuthState;
import com.aideep.domain.auth.dto.SignupTicket;
import com.aideep.domain.auth.dto.request.OAuthSignupRequest;
import com.aideep.domain.auth.dto.response.OAuthLinkResponse;
import com.aideep.domain.auth.dto.response.TokensResponse;
import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.entity.OAuthAccount;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.auth.repository.OAuthAccountRepository;
import com.aideep.domain.onboarding.dto.TermConsent;
import com.aideep.domain.onboarding.service.UserTermAgreementService;
import com.aideep.global.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class OAuthService {
    private final RedisAuthStore redisAuthStore;
    private final GoogleOAuthClient googleOAuthClient;
    private final AuthUserRepository authUserRepository;
    private final OAuthAccountRepository oAuthAccountRepository;
    private final AuthDatabase authDatabase;
    private final AuthService authService;
    private final SecureRandom secureRandom = new SecureRandom();

    public OAuthService(RedisAuthStore redisAuthStore, GoogleOAuthClient googleOAuthClient,
                        AuthUserRepository authUserRepository,
                        OAuthAccountRepository oAuthAccountRepository, AuthDatabase authDatabase,
                        AuthService authService) {
        this.redisAuthStore = redisAuthStore;
        this.googleOAuthClient = googleOAuthClient;
        this.authUserRepository = authUserRepository;
        this.oAuthAccountRepository = oAuthAccountRepository;
        this.authDatabase = authDatabase;
        this.authService = authService;
    }

    private String randomToken(int size) {
        byte[] bytes = new byte[size];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String initiate(UUID userId) {
        String nonce = randomToken(16);
        String url = googleOAuthClient.authorizeUrl(nonce);
        if (!redisAuthStore.putOnce("oauth:link_state:" + nonce,
                new OAuthState(userId == null ? "login" : "link", userId == null ? null : userId.toString())))
            throw new IllegalStateException("OAuth nonce collision");
        return url;
    }

    public OAuthResult callback(String state, String code, String error) {
        if (state == null || state.isBlank()) throw new BusinessException(AuthError.OAUTH_STATE_MISSING);
        OAuthState saved = redisAuthStore.consume("oauth:link_state:" + state, OAuthState.class);
        if (saved == null || !("login".equals(saved.mode()) || "link".equals(saved.mode())))
            throw new BusinessException(AuthError.OAUTH_STATE_INVALID);
        if (error != null) throw new BusinessException(AuthError.GOOGLE_AUTHENTICATION_FAILED);
        GoogleProfile profile = googleOAuthClient.exchange(code);
        if ("link".equals(saved.mode())) {
            try {
                authDatabase.link(UUID.fromString(saved.user_id()), profile);
            } catch (DataIntegrityViolationException e) {
                throw new BusinessException(AuthError.GOOGLE_ACCOUNT_ALREADY_LINKED);
            }
            return OAuthResult.linked();
        }
        var account = oAuthAccountRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull("google", profile.id());
        if (account.isPresent()) {
            AuthUser user = authUserRepository.findById(account.get().getUserId())
                    .orElseThrow(() -> new BusinessException(AuthError.USER_NOT_FOUND));
            return OAuthResult.login(authService.issue(AuthService.identity(user)));
        }
        if (authUserRepository.findByEmail(profile.email()).isPresent()) {
            try {
                Thread.sleep(ThreadLocalRandom.current().nextInt(200, 301));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("OAuth request interrupted", e);
            }
            throw new BusinessException(AuthError.OAUTH_EMAIL_CONFLICT);
        }
        String ticket = randomToken(32);
        if (!redisAuthStore.putOnce("oauth:signup_ticket:" + ticket,
                new SignupTicket("google", profile.id(), profile.email(), profile.displayName())))
            throw new IllegalStateException("OAuth signup ticket collision");
        return OAuthResult.signup(ticket);
    }

    public TokensResponse complete(OAuthSignupRequest body) {
        SignupTicket ticket = redisAuthStore.consume("oauth:signup_ticket:" + body.ticket(), SignupTicket.class);
        if (ticket == null) throw new BusinessException(AuthError.SIGNUP_TICKET_INVALID);
        AuthUser user;
        try {
            user = authDatabase.createOAuthUser(ticket, body.username(),
                    new TermConsent(body.terms(), body.privacy(), body.marketing()));
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(AuthError.OAUTH_SIGNUP_CONFLICT);
        }
        return authService.issue(AuthService.identity(user));
    }

    public List<OAuthLinkResponse> links(UUID userId) {
        return oAuthAccountRepository.findByUserIdAndDeletedAtIsNull(userId).stream()
                .map(a -> new OAuthLinkResponse(a.getProvider(), a.getEmail(), a.getCreatedAt())).toList();
    }

    public void unlink(UUID userId, String provider) {
        authDatabase.unlink(userId, provider);
    }

    @Service
    public static class AuthDatabase {
        private final AuthUserRepository authUserRepository;
        private final OAuthAccountRepository oAuthAccountRepository;
        private final UserTermAgreementService userTermAgreementService;
        private final JdbcTemplate jdbcTemplate;
        private final Clock clock;

        public AuthDatabase(AuthUserRepository authUserRepository, OAuthAccountRepository oAuthAccountRepository,
                            UserTermAgreementService userTermAgreementService, JdbcTemplate jdbcTemplate,
                            Clock clock) {
            this.authUserRepository = authUserRepository;
            this.oAuthAccountRepository = oAuthAccountRepository;
            this.userTermAgreementService = userTermAgreementService;
            this.jdbcTemplate = jdbcTemplate;
            this.clock = clock;
        }

        @Transactional
        public AuthUser createUser(String email, String password) {
            return authUserRepository.saveAndFlush(new AuthUser(email, password, clock.instant()));
        }

        /** 회원가입 경로. 사용자와 약관 동의를 한 트랜잭션에서 저장해 둘 중 하나만 남는 상태를 막는다. */
        @Transactional
        public AuthUser createUser(String email, String password, String username, TermConsent termConsent) {
            AuthUser user = authUserRepository.saveAndFlush(
                    new AuthUser(email, password, username, clock.instant()));
            userTermAgreementService.record(user.getId(), termConsent);
            return user;
        }

        @Transactional
        public AuthUser createOAuthUser(SignupTicket ticket) {
            AuthUser user = createUser(ticket.email(), null);
            oAuthAccountRepository.saveAndFlush(
                    new OAuthAccount(user.getId(), ticket.provider(), ticket.providerUserId(), ticket.email(),
                            clock.instant()));
            return user;
        }

        @Transactional
        public AuthUser createOAuthUser(SignupTicket ticket, String username, TermConsent termConsent) {
            AuthUser user = createOAuthUser(ticket);
            user.changeUsername(username, clock.instant());
            userTermAgreementService.record(user.getId(), termConsent);
            return authUserRepository.saveAndFlush(user);
        }

        @Transactional
        public void link(UUID userId, GoogleProfile profile) {
            authUserRepository.lockById(userId).orElseThrow(() -> new BusinessException(AuthError.USER_NOT_FOUND));
            var existing = oAuthAccountRepository.findByProviderAndProviderUserIdAndDeletedAtIsNull("google",
                    profile.id());
            if (existing.isPresent() && !existing.get().getUserId().equals(userId))
                throw new BusinessException(AuthError.GOOGLE_ACCOUNT_ALREADY_LINKED);
            if (oAuthAccountRepository.findByUserIdAndProviderAndDeletedAtIsNull(userId, "google").isPresent())
                throw new BusinessException(AuthError.PROVIDER_ALREADY_LINKED);
            var account = oAuthAccountRepository.findByUserIdAndProviderAndProviderUserId(userId, "google",
                            profile.id())
                    .orElseGet(
                            () -> new OAuthAccount(userId, "google", profile.id(), profile.email(), clock.instant()));
            account.revive(profile.email(), clock.instant());
            oAuthAccountRepository.saveAndFlush(account);
        }

        @Transactional
        public void deleteUser(UUID userId) {
            AuthUser user = authUserRepository.lockById(userId)
                    .orElseThrow(() -> new BusinessException(AuthError.USER_NOT_FOUND));
            authUserRepository.delete(user);
            authUserRepository.flush();
        }

        @Transactional
        public void unlink(UUID userId, String provider) {
            AuthUser user = authUserRepository.lockById(userId)
                    .orElseThrow(() -> new BusinessException(AuthError.USER_NOT_FOUND));
            OAuthAccount account = oAuthAccountRepository.findByUserIdAndProviderAndDeletedAtIsNull(userId, provider)
                    .orElseThrow(() -> new BusinessException(AuthError.OAUTH_ACCOUNT_NOT_LINKED));
            if (user.getPassword() == null && oAuthAccountRepository.countByUserIdAndDeletedAtIsNull(userId) <= 1)
                throw new BusinessException(AuthError.LAST_AUTH_METHOD);
            account.unlink(clock.instant());
        }

        @Transactional
        public AuthUser createGuest(UUID workspaceId, String email, String name, String password) {
            var workspaces = jdbcTemplate.queryForList(
                    "select workspace_id from workspaces where workspace_id=? and deleted_at is null for key share",
                    UUID.class, workspaceId);
            if (workspaces.isEmpty()) throw new BusinessException(AuthError.DEMO_WORKSPACE_NOT_CONFIGURED);
            AuthUser guest = createUser(email, password);
            jdbcTemplate.update(
                    "insert into users_workspaces (user_id, workspace_id, role) values (?, ?, cast(? as workspace_role_enum))",
                    guest.getId(), workspaceId, "VIEWER");
            return guest;
        }
    }
}
