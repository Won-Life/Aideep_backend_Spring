package com.aideep.domain.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("recall")
public record RecallProperties(
        String apiUrl,
        String apiKey,
        String botName
) {
}
