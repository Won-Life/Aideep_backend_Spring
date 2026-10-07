package com.aideep.domain.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("recall")
public record RecallProperties(
        String apiUrl,
        String apiKey,
        String botName,

        /**
         * Recall 대시보드에 등록한 웹훅 엔드포인트의 서명 시크릿. {@code whsec_} 접두사를 포함한 원본 값이다.
         */
        String webhookSecret
) {
}
