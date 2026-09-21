package com.aideep.domain.auth;

import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.repository.AuthUserRepository;
import com.aideep.domain.auth.service.AuthService;
import com.aideep.domain.auth.service.JwtTokenService;
import com.aideep.domain.auth.service.OAuthService;
import com.aideep.domain.auth.service.RedisAuthStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServicePolicyTest {
    @Test
    void masterIssuanceIsDisabledInProductionEvenIfDevIsAlsoActive() {
        var authUserRepository = mock(AuthUserRepository.class);
        var passwordEncoder = mock(PasswordEncoder.class);
        var jwtTokenService = mock(JwtTokenService.class);
        var redisAuthStore = mock(RedisAuthStore.class);
        var user = new AuthUser("user@example.com", "User", "hash", java.time.Instant.now());
        when(authUserRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password", "hash")).thenReturn(true);
        for (String[] profiles : new String[][]{{"prod"}, {"dev", "prod"}, {"production"}, {"test"}}) {
            var mockEnvironment = new MockEnvironment();
            mockEnvironment.setActiveProfiles(profiles);
            var authService = new AuthService(authUserRepository, mock(OAuthService.AuthDatabase.class), redisAuthStore,
                    jwtTokenService, passwordEncoder,
                    JwtTokenServiceTest.properties(JwtTokenServiceTest.SECRET), mockEnvironment, Clock.systemUTC());
        }
        verifyNoInteractions(jwtTokenService, redisAuthStore);
    }
}
