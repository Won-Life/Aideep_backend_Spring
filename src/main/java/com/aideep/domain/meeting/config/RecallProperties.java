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
        String transcriptWebhookUrl,

        /**
         * 녹화가 시작된 뒤 봇이 회의에 머무를 수 있는 최대 시간(초). 회의 길이 상한이다.
         * <p>
         * 값을 보내지 않으면 Recall 쪽 길이 상한이 사실상 없어진다. 봇이 이 시간을 넘기면 스스로 나가고, 평소와 같은 상태 웹훅이 도착해 회의 행이
         * 기존 경로로 닫힌다. 0 이하이거나 비어 있으면 상한을 보내지 않는다.
         */
        Integer recordingTimeoutSeconds,

        /**
         * 녹화가 시작되기 전 대기 상태(대기실, 아무도 들어오지 않음)로 기다릴 수 있는 최대 시간(초).
         * <p>
         * 녹화까지 가지 못한 봇은 길이 상한에 걸리지 않아 따로 끊어야 한다. 0 이하이거나 비어 있으면 상한을 보내지 않는다.
         */
        Integer waitingTimeoutSeconds
) {
}
