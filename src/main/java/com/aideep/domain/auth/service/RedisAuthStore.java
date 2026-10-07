package com.aideep.domain.auth.service;

import com.aideep.domain.auth.exception.AuthError;
import com.aideep.global.exception.BusinessException;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class RedisAuthStore {
    public static final int LOGIN_FAILURE_LIMIT = 5;
    public static final long LOGIN_LOCK_SECONDS = Duration.ofMinutes(10).toSeconds();
    public static final Duration PASSWORD_RESET_TOKEN_TTL = Duration.ofMinutes(30);

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisAuthStore(StringRedisTemplate stringRedisTemplate, ObjectMapper objectMapper) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public static String refreshKey(String id) {
        return "refreshToken:" + id;
    }

    public void saveRefresh(String id, String token) {
        put(refreshKey(id), token, Duration.ofDays(7));
    }

    public boolean rotateRefresh(String id, String oldToken, String newToken) {
        return Long.valueOf(1).equals(stringRedisTemplate.execute(new DefaultRedisScript<>("""
                if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
                redis.call('SET', KEYS[1], ARGV[2], 'EX', 604800)
                return 1
                """, Long.class), List.of(refreshKey(id)), oldToken, newToken));
    }

    public static String loginFailureKey(String email) {
        return "loginFail:" + email;
    }

    public static String loginLockKey(String email) {
        return "loginLock:" + email;
    }

    public static String passwordResetKey(String token) {
        return "passwordReset:" + token;
    }

    /** 로그인 시도 전에 호출한다. 잠긴 계정은 비밀번호가 일치해도 통과시키지 않는다. */
    public void assertNotLocked(String email) {
        if (get(loginLockKey(email)) != null) throw new BusinessException(AuthError.ACCOUNT_LOCKED);
    }

    /**
     * 로그인 실패 횟수를 1 증가시키고, {@link #LOGIN_FAILURE_LIMIT}회에 도달하면
     * {@link #LOGIN_LOCK_SECONDS}초 동안 계정을 잠근다.
     */
    public void recordLoginFailure(String email) {
        stringRedisTemplate.execute(new DefaultRedisScript<>("""
                        local attempts=redis.call('INCR', KEYS[1])
                        if attempts == 1 then redis.call('EXPIRE', KEYS[1], ARGV[2]) end
                        if attempts >= tonumber(ARGV[1]) then
                          redis.call('DEL', KEYS[1])
                          redis.call('SET', KEYS[2], '1', 'EX', ARGV[2])
                        end
                        return attempts
                        """, Long.class), List.of(loginFailureKey(email), loginLockKey(email)),
                Integer.toString(LOGIN_FAILURE_LIMIT), Long.toString(LOGIN_LOCK_SECONDS));
    }

    /** 로그인 성공이나 비밀번호 재설정으로 실패 이력을 초기화한다. */
    public void clearLoginFailures(String email) {
        stringRedisTemplate.delete(List.of(loginFailureKey(email), loginLockKey(email)));
    }

    public void savePasswordResetToken(String token, String userId) {
        put(passwordResetKey(token), userId, PASSWORD_RESET_TOKEN_TTL);
    }

    /** 재설정 토큰은 1회만 사용할 수 있도록 조회와 동시에 삭제한다. */
    public String consumePasswordResetToken(String token) {
        return stringRedisTemplate.opsForValue().getAndDelete(passwordResetKey(token));
    }

    public void deleteRefresh(String id) {
        delete(refreshKey(id));
    }

    public void logout(String id, String token, long remainingSeconds) {
        stringRedisTemplate.execute(new DefaultRedisScript<>("""
                        redis.call('DEL', KEYS[1])
                        redis.call('SET', KEYS[2], '1', 'EX', ARGV[1])
                        return 1
                        """, Long.class), List.of(refreshKey(id), "blacklist:" + token),
                Long.toString(Math.max(1, remainingSeconds)));
    }

    public void validateToken(String token, String userId, boolean master) {
        if (get("blacklist:" + token) != null) throw new BusinessException(AuthError.TOKEN_REVOKED);
        if (master && !token.equals(get("masterToken:" + userId)))
            throw new BusinessException(AuthError.MASTER_TOKEN_REVOKED);
    }

    public void saveMaster(String id, String token) {
        put("masterToken:" + id, token, Duration.ofDays(30));
    }

    public String get(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    public void put(String key, String value, Duration ttl) {
        stringRedisTemplate.opsForValue().set(key, value, ttl);
    }

    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    public boolean putOnce(String key, Object value) {
        return Boolean.TRUE.equals(
                stringRedisTemplate.opsForValue()
                        .setIfAbsent(key, objectMapper.writeValueAsString(value), Duration.ofMinutes(5)));
    }

    public <T> T consume(String key, Class<T> type) {
        String value = stringRedisTemplate.opsForValue().getAndDelete(key);
        return value == null ? null : objectMapper.readValue(value, type);
    }

    public void saveCode(String email, String code, long generatedAt) {
        put("auth:" + email, objectMapper.writeValueAsString(new EmailCode(code, 0, generatedAt)),
                Duration.ofSeconds(180));
    }

    public void verifyCode(String email, String code) {
        Long result = stringRedisTemplate.execute(new DefaultRedisScript<>("""
                local raw=redis.call('GET', KEYS[1])
                if not raw then return -1 end
                local data=cjson.decode(raw)
                if data.code ~= ARGV[1] then
                  data.attempts=data.attempts+1
                  if data.attempts >= 5 then redis.call('DEL',KEYS[1]); return -3 end
                  redis.call('SET',KEYS[1],cjson.encode(data),'KEEPTTL'); return -2
                end
                redis.call('DEL',KEYS[1])
                redis.call('SET',KEYS[2],'1','EX',600)
                return 1
                """, Long.class), List.of("auth:" + email, "verified:" + email), code);
        if (Long.valueOf(-1).equals(result)) throw new BusinessException(AuthError.VERIFICATION_CODE_NOT_FOUND);
        if (Long.valueOf(-3).equals(result)) throw new BusinessException(AuthError.VERIFICATION_ATTEMPTS_EXCEEDED);
        if (!Long.valueOf(1).equals(result)) throw new BusinessException(AuthError.VERIFICATION_CODE_MISMATCH);
    }

    private record EmailCode(String code, int attempts, long generatedAt) {
    }
}
