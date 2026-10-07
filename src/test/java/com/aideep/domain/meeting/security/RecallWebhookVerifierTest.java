package com.aideep.domain.meeting.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aideep.domain.meeting.config.RecallProperties;
import com.aideep.domain.meeting.exception.MeetingError;
import com.aideep.global.exception.BusinessException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;

class RecallWebhookVerifierTest {

    private static final Instant NOW = Instant.parse("2026-10-06T01:02:03Z");
    private static final String WEBHOOK_ID = "msg_2abcDEF";
    private static final String TIMESTAMP = String.valueOf(NOW.getEpochSecond());
    private static final String SECRET_KEY = Base64.getEncoder().encodeToString("recall-webhook-key".getBytes(
            StandardCharsets.UTF_8));
    private static final byte[] BODY = """
            {"event":"bot.status_change","data":{"bot_id":"33333333-3333-4333-8333-333333333333"}}
            """.getBytes(StandardCharsets.UTF_8);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void acceptsSignatureComputedOverRawBody() {
        RecallWebhookVerifier recallWebhookVerifier = verifier("whsec_" + SECRET_KEY);

        recallWebhookVerifier.verify(WEBHOOK_ID, TIMESTAMP, signature(TIMESTAMP, BODY), BODY);
    }

    @Test
    void acceptsSecretWithoutWhsecPrefix() {
        RecallWebhookVerifier recallWebhookVerifier = verifier(SECRET_KEY);

        recallWebhookVerifier.verify(WEBHOOK_ID, TIMESTAMP, signature(TIMESTAMP, BODY), BODY);
    }

    @Test
    void acceptsAnyEntryWhileSecretIsRotating() {
        RecallWebhookVerifier recallWebhookVerifier = verifier("whsec_" + SECRET_KEY);
        String rotating = "v1,ZGVhZGJlZWY= " + signature(TIMESTAMP, BODY);

        recallWebhookVerifier.verify(WEBHOOK_ID, TIMESTAMP, rotating, BODY);
    }

    @Test
    void rejectsSignatureOfReserializedBody() {
        RecallWebhookVerifier recallWebhookVerifier = verifier("whsec_" + SECRET_KEY);
        byte[] reserialized = """
                {"event":"bot.status_change","data":{"bot_id":"33333333-3333-4333-8333-333333333333"}}""".getBytes(
                StandardCharsets.UTF_8);

        assertThatThrownBy(() -> recallWebhookVerifier.verify(
                WEBHOOK_ID, TIMESTAMP, signature(TIMESTAMP, reserialized), BODY))
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode())
                .isEqualTo(MeetingError.WEBHOOK_SIGNATURE_INVALID);
    }

    @Test
    void rejectsMissingHeaders() {
        RecallWebhookVerifier recallWebhookVerifier = verifier("whsec_" + SECRET_KEY);
        String validSignature = signature(TIMESTAMP, BODY);

        assertThat(rejected(() -> recallWebhookVerifier.verify(null, TIMESTAMP, validSignature, BODY))).isTrue();
        assertThat(rejected(() -> recallWebhookVerifier.verify(WEBHOOK_ID, null, validSignature, BODY))).isTrue();
        assertThat(rejected(() -> recallWebhookVerifier.verify(WEBHOOK_ID, TIMESTAMP, null, BODY))).isTrue();
        assertThat(rejected(() -> recallWebhookVerifier.verify(WEBHOOK_ID, TIMESTAMP, validSignature, null))).isTrue();
    }

    @Test
    void rejectsUnknownSignatureVersionAndMalformedBase64() {
        RecallWebhookVerifier recallWebhookVerifier = verifier("whsec_" + SECRET_KEY);
        String valid = signature(TIMESTAMP, BODY);

        assertThat(rejected(() -> recallWebhookVerifier.verify(
                WEBHOOK_ID, TIMESTAMP, valid.replace("v1,", "v2,"), BODY))).isTrue();
        assertThat(rejected(() -> recallWebhookVerifier.verify(WEBHOOK_ID, TIMESTAMP, "v1,not*base64", BODY))).isTrue();
        assertThat(rejected(() -> recallWebhookVerifier.verify(WEBHOOK_ID, TIMESTAMP, "garbage", BODY))).isTrue();
    }

    @Test
    void rejectsTimestampOutsideTolerance() {
        RecallWebhookVerifier recallWebhookVerifier = verifier("whsec_" + SECRET_KEY);
        String stale = String.valueOf(NOW.minus(RecallWebhookVerifier.TOLERANCE).minusSeconds(1).getEpochSecond());
        String future = String.valueOf(NOW.plus(RecallWebhookVerifier.TOLERANCE).plusSeconds(1).getEpochSecond());

        assertThat(rejected(() -> recallWebhookVerifier.verify(
                WEBHOOK_ID, stale, signature(stale, BODY), BODY))).isTrue();
        assertThat(rejected(() -> recallWebhookVerifier.verify(
                WEBHOOK_ID, future, signature(future, BODY), BODY))).isTrue();
        assertThat(rejected(() -> recallWebhookVerifier.verify(
                WEBHOOK_ID, "not-a-number", signature("not-a-number", BODY), BODY))).isTrue();
    }

    @Test
    void failsFastWhenSecretIsNotConfigured() {
        RecallWebhookVerifier recallWebhookVerifier = verifier("");

        assertThatThrownBy(() -> recallWebhookVerifier.verify(WEBHOOK_ID, TIMESTAMP, "v1,ZGVhZGJlZWY=", BODY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECALL_WEBHOOK_SECRET");
    }

    private boolean rejected(Runnable verification) {
        try {
            verification.run();
            return false;
        } catch (BusinessException exception) {
            return exception.getErrorCode() == MeetingError.WEBHOOK_SIGNATURE_INVALID;
        }
    }

    private RecallWebhookVerifier verifier(String webhookSecret) {
        RecallProperties recallProperties = new RecallProperties(
                "https://example.test/api/v1/bot/", "recall-key", "AIDEEP Notetaker", webhookSecret);
        return new RecallWebhookVerifier(recallProperties, clock);
    }

    private String signature(String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(SECRET_KEY), "HmacSHA256"));
            mac.update((WEBHOOK_ID + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return "v1," + Base64.getEncoder().encodeToString(mac.doFinal());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
