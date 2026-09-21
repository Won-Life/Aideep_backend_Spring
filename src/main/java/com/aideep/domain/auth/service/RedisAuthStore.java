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
        if (Long.valueOf(-1).equals(result)) throw new BusinessException(AuthError.VERIFICATION_CODE_EXPIRED);
        if (Long.valueOf(-3).equals(result)) throw new BusinessException(AuthError.VERIFICATION_ATTEMPTS_EXCEEDED);
        if (!Long.valueOf(1).equals(result)) throw new BusinessException(AuthError.VERIFICATION_CODE_MISMATCH);
    }

    private record EmailCode(String code, int attempts, long generatedAt) {
    }
}
