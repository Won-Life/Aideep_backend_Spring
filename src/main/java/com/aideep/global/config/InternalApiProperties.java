package com.aideep.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;

/**
 * 내부 서버 간 API의 공유 시크릿. 환경변수로만 주입하며 저장소에 커밋하지 않는다.
 * <p>
 * 값이 없으면 애플리케이션 기동을 실패시킨다. 인증 없이 열려 있는 내부 API를 만들지 않기 위한 fail-fast다.
 */
@ConfigurationProperties("internal.api")
public record InternalApiProperties(String key) {

    public static final int MIN_KEY_LENGTH = 32;

    public InternalApiProperties {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("INTERNAL_API_KEY is required");
        }
        if (key.getBytes(StandardCharsets.UTF_8).length < MIN_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "INTERNAL_API_KEY must be at least " + MIN_KEY_LENGTH + " bytes");
        }
    }
}
