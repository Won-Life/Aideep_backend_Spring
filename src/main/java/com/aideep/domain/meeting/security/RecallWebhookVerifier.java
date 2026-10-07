package com.aideep.domain.meeting.security;

import com.aideep.domain.meeting.config.RecallProperties;
import com.aideep.domain.meeting.exception.MeetingError;
import com.aideep.global.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

/**
 * Recall(Svix) 웹훅의 {@code webhook-id}/{@code webhook-timestamp}/{@code webhook-signature}를 검증한다.
 * <p>
 * 서명 대상은 {@code {webhook-id}.{webhook-timestamp}.{원문 바디}}이므로 파싱한 DTO를 재직렬화한 바디를 쓰면 검증이 깨진다.
 */
@Component
public class RecallWebhookVerifier {

    public static final String ID_HEADER = "webhook-id";
    public static final String TIMESTAMP_HEADER = "webhook-timestamp";
    public static final String SIGNATURE_HEADER = "webhook-signature";

    static final Duration TOLERANCE = Duration.ofMinutes(5);

    private static final String SECRET_PREFIX = "whsec_";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_VERSION = "v1";

    private final RecallProperties recallProperties;
    private final Clock clock;

    public RecallWebhookVerifier(RecallProperties recallProperties, Clock clock) {
        this.recallProperties = recallProperties;
        this.clock = clock;
    }

    /**
     * 검증에 실패하면 {@link MeetingError#WEBHOOK_SIGNATURE_INVALID}를 던진다. Recall은 2xx가 아닌 응답을 재시도하므로 호출자는 이
     * 예외를 영구 실패로 다루지 않는다.
     */
    public void verify(String webhookId, String webhookTimestamp, String webhookSignature, byte[] rawBody) {
        if (isBlank(webhookId) || isBlank(webhookTimestamp) || isBlank(webhookSignature) || rawBody == null) {
            throw new BusinessException(MeetingError.WEBHOOK_SIGNATURE_INVALID);
        }
        requireFreshTimestamp(webhookTimestamp);
        byte[] expected = sign(webhookId, webhookTimestamp, rawBody);
        if (!matchesAny(webhookSignature, expected)) {
            throw new BusinessException(MeetingError.WEBHOOK_SIGNATURE_INVALID);
        }
    }

    private void requireFreshTimestamp(String webhookTimestamp) {
        Instant timestamp;
        try {
            timestamp = Instant.ofEpochSecond(Long.parseLong(webhookTimestamp.trim()));
        } catch (NumberFormatException exception) {
            throw new BusinessException(MeetingError.WEBHOOK_SIGNATURE_INVALID);
        }
        Duration difference = Duration.between(timestamp, clock.instant()).abs();
        if (difference.compareTo(TOLERANCE) > 0) {
            throw new BusinessException(MeetingError.WEBHOOK_SIGNATURE_INVALID);
        }
    }

    /**
     * 시크릿 로테이션 중에는 {@code v1,<sig> v1,<sig>}처럼 여러 서명이 공백으로 이어져 오므로 전부 비교한다.
     */
    private boolean matchesAny(String webhookSignature, byte[] expected) {
        boolean matched = false;
        for (String candidate : webhookSignature.trim().split("\\s+")) {
            int separator = candidate.indexOf(',');
            if (separator < 0 || !SIGNATURE_VERSION.equals(candidate.substring(0, separator))) {
                continue;
            }
            byte[] actual = decodeBase64(candidate.substring(separator + 1));
            if (actual != null && MessageDigest.isEqual(expected, actual)) {
                matched = true;
            }
        }
        return matched;
    }

    private byte[] sign(String webhookId, String webhookTimestamp, byte[] rawBody) {
        byte[] signingKey = signingKey();
        byte[] prefix = (webhookId + "." + webhookTimestamp + ".").getBytes(StandardCharsets.UTF_8);
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            mac.update(prefix);
            mac.update(rawBody);
            return mac.doFinal();
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("Recall webhook signature could not be computed", exception);
        }
    }

    private byte[] signingKey() {
        String webhookSecret = recallProperties.webhookSecret();
        if (isBlank(webhookSecret)) {
            throw new IllegalStateException("RECALL_WEBHOOK_SECRET is required");
        }
        String encodedKey = webhookSecret.startsWith(SECRET_PREFIX)
                ? webhookSecret.substring(SECRET_PREFIX.length())
                : webhookSecret;
        byte[] signingKey = decodeBase64(encodedKey);
        if (signingKey == null) {
            throw new IllegalStateException("RECALL_WEBHOOK_SECRET must be base64 encoded");
        }
        return signingKey;
    }

    private byte[] decodeBase64(String value) {
        try {
            return Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
