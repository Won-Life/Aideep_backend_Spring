package com.aideep.domain.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth")
public record AuthProperties(String jwtSecret, String frontendUrl, String googleClientId,
                             String googleClientSecret, String googleCallbackUrl, String googleAuthorizeUrl,
                             String googleTokenUrl, String googleUserInfoUrl, String mailUser, String mailPass,
                             String masterUserIds, String demoSecret, String demoWorkspaceId) {
}

