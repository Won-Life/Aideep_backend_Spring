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
        String webhookSecret,

        /**
         * 실시간 transcript({@code transcript.data})를 받을 AI 서버 웹훅 URL.
         * <p>
         * 대시보드 웹훅과 달리 봇 생성 요청의 {@code recording_config.realtime_endpoints}로만 지정할 수 있어서, 봇마다 이 값을 실어
         * 보낸다. 비어 있으면 realtime endpoint 없이 봇을 만들고 실시간 전사는 수신하지 않는다.
         */
        String transcriptWebhookUrl
) {
}
