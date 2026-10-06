package com.aideep.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.auth.exception.AuthError;
import com.aideep.global.exception.BusinessException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.ObjectMapper;

/** 로그인 잠금과 비밀번호 재설정 토큰의 Lua/TTL 동작을 실제 Redis로 검증한다. */
@Testcontainers
class RedisAuthStoreIntegrationTest {

    private static final String EMAIL = "user@example.com";

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static LettuceConnectionFactory lettuceConnectionFactory;

    private StringRedisTemplate stringRedisTemplate;
    private RedisAuthStore redisAuthStore;

    @BeforeAll
    static void startFactory() {
        lettuceConnectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        lettuceConnectionFactory.afterPropertiesSet();
    }

    @AfterAll
    static void stopFactory() {
        lettuceConnectionFactory.destroy();
    }

    @BeforeEach
    void setUp() {
        stringRedisTemplate = new StringRedisTemplate(lettuceConnectionFactory);
        stringRedisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        redisAuthStore = new RedisAuthStore(stringRedisTemplate, new ObjectMapper());
    }

    @Test
    void doesNotLockBeforeTheFifthFailure() {
        for (int attempt = 0; attempt < RedisAuthStore.LOGIN_FAILURE_LIMIT - 1; attempt++) {
            redisAuthStore.recordLoginFailure(EMAIL);
        }

        redisAuthStore.assertNotLocked(EMAIL);
        assertThat(stringRedisTemplate.opsForValue().get(RedisAuthStore.loginFailureKey(EMAIL))).isEqualTo("4");
    }

    @Test
    void locksForTenMinutesOnTheFifthFailure() {
        for (int attempt = 0; attempt < RedisAuthStore.LOGIN_FAILURE_LIMIT; attempt++) {
            redisAuthStore.recordLoginFailure(EMAIL);
        }

        assertThatThrownBy(() -> redisAuthStore.assertNotLocked(EMAIL))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(AuthError.ACCOUNT_LOCKED));
        assertThat(stringRedisTemplate.getExpire(RedisAuthStore.loginLockKey(EMAIL)))
                .isBetween(RedisAuthStore.LOGIN_LOCK_SECONDS - 5, RedisAuthStore.LOGIN_LOCK_SECONDS);
        assertThat(stringRedisTemplate.hasKey(RedisAuthStore.loginFailureKey(EMAIL)))
                .as("잠금되면 실패 카운터는 비운다").isFalse();
    }

    @Test
    void expiresFailureCounterWithinTheLockWindow() {
        redisAuthStore.recordLoginFailure(EMAIL);

        assertThat(stringRedisTemplate.getExpire(RedisAuthStore.loginFailureKey(EMAIL)))
                .isBetween(1L, RedisAuthStore.LOGIN_LOCK_SECONDS);
    }

    @Test
    void clearsBothCounterAndLock() {
        for (int attempt = 0; attempt < RedisAuthStore.LOGIN_FAILURE_LIMIT; attempt++) {
            redisAuthStore.recordLoginFailure(EMAIL);
        }

        redisAuthStore.clearLoginFailures(EMAIL);

        redisAuthStore.assertNotLocked(EMAIL);
        assertThat(stringRedisTemplate.hasKey(RedisAuthStore.loginFailureKey(EMAIL))).isFalse();
    }

    @Test
    void consumesPasswordResetTokenOnlyOnce() {
        redisAuthStore.savePasswordResetToken("token", "11111111-1111-4111-8111-111111111111");

        assertThat(redisAuthStore.consumePasswordResetToken("token"))
                .isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(redisAuthStore.consumePasswordResetToken("token")).isNull();
    }

    @Test
    void keepsPasswordResetTokenForThirtyMinutes() {
        redisAuthStore.savePasswordResetToken("token", "11111111-1111-4111-8111-111111111111");

        assertThat(stringRedisTemplate.getExpire(RedisAuthStore.passwordResetKey("token")))
                .isBetween(RedisAuthStore.PASSWORD_RESET_TOKEN_TTL.toSeconds() - 5,
                        RedisAuthStore.PASSWORD_RESET_TOKEN_TTL.toSeconds());
    }
}
