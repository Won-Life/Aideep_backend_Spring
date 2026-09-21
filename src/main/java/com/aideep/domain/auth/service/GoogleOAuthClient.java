package com.aideep.domain.auth.service;

import com.aideep.domain.auth.config.AuthProperties;
import com.aideep.domain.auth.dto.GoogleProfile;
import com.aideep.domain.auth.exception.AuthError;
import com.aideep.global.exception.BusinessException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GoogleOAuthClient {
    private final AuthProperties authProperties;
    private final RestClient restClient;

    public GoogleOAuthClient(AuthProperties authProperties) {
        this.authProperties = authProperties;
        var jdkClientHttpRequestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        jdkClientHttpRequestFactory.setReadTimeout(Duration.ofSeconds(10));
        restClient = RestClient.builder().requestFactory(jdkClientHttpRequestFactory).build();
    }

    private void requireConfiguration() {
        if (blank(authProperties.googleClientId()) || blank(authProperties.googleClientSecret()) || blank(
                authProperties.googleCallbackUrl()))
            throw new IllegalStateException(
                    "GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET and GOOGLE_CALLBACK_URL are required");
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public String authorizeUrl(String nonce) {
        requireConfiguration();
        return UriComponentsBuilder.fromUriString(authProperties.googleAuthorizeUrl())
                .queryParam("client_id", authProperties.googleClientId())
                .queryParam("redirect_uri", authProperties.googleCallbackUrl())
                .queryParam("response_type", "code").queryParam("scope", "email profile").queryParam("state", nonce)
                .queryParam("access_type", "offline").queryParam("prompt", "consent").build().encode().toUriString();
    }

    public GoogleProfile exchange(String code) {
        requireConfiguration();
        if (blank(code)) throw new BusinessException(AuthError.GOOGLE_CODE_MISSING);
        var body = new LinkedMultiValueMap<String, String>();
        body.add("code", code);
        body.add("client_id", authProperties.googleClientId());
        body.add("client_secret", authProperties.googleClientSecret());
        body.add("redirect_uri", authProperties.googleCallbackUrl());
        body.add("grant_type", "authorization_code");
        Map<?, ?> token;
        Map<?, ?> profile;
        try {
            token = restClient.post().uri(authProperties.googleTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(body).retrieve().body(Map.class);
            if (token == null || !(token.get("access_token") instanceof String accessToken) || accessToken.isBlank())
                throw new IllegalStateException("Google token response is invalid");
            profile = restClient.get().uri(authProperties.googleUserInfoUrl())
                    .headers(h -> h.setBearerAuth(accessToken))
                    .retrieve().body(Map.class);
        } catch (RestClientResponseException e) {
            // Do not include Google's response body (which may contain credentials) in application logs.
            if (e.getStatusCode().is4xxClientError())
                throw new BusinessException(AuthError.GOOGLE_AUTHENTICATION_FAILED);
            throw new IllegalStateException("Google upstream failed (HTTP " + e.getStatusCode().value() + ")");
        }
        if (profile == null || !(profile.get("email") instanceof String email) || email.isBlank() ||
                !(Boolean.TRUE.equals(profile.get("email_verified")) || "true".equals(profile.get("email_verified"))))
            throw new BusinessException(AuthError.GOOGLE_EMAIL_UNVERIFIED);
        if (!(profile.get("sub") instanceof String subject) || subject.isBlank())
            throw new BusinessException(AuthError.GOOGLE_PROFILE_INVALID);
        return new GoogleProfile(subject, email, profile.get("name") instanceof String name ? name : "");
    }
}
