package com.aideep.domain.onboarding.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class UserTermAgreementTest {

    private static final UUID USER_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final Instant NOW = Instant.parse("2026-10-05T10:00:00Z");

    @Test
    void agreeRecordsAgreementTime() {
        UserTermAgreement userTermAgreement = UserTermAgreement.agree(
                USER_ID, TermAgreementType.TERMS_OF_SERVICE, NOW, NOW);

        assertThat(userTermAgreement.getUserId()).isEqualTo(USER_ID);
        assertThat(userTermAgreement.getTermType()).isEqualTo(TermAgreementType.TERMS_OF_SERVICE);
        assertThat(userTermAgreement.isAgreed()).isTrue();
        assertThat(userTermAgreement.getAgreedAt()).isEqualTo(NOW);
        assertThat(userTermAgreement.getRevokedAt()).isNull();
    }

    @Test
    void declineLeavesOptionalTermWithoutAgreementTime() {
        UserTermAgreement userTermAgreement = UserTermAgreement.decline(USER_ID, TermAgreementType.MARKETING, NOW);

        assertThat(userTermAgreement.isAgreed()).isFalse();
        assertThat(userTermAgreement.getAgreedAt()).isNull();
    }

    @Test
    void revokeKeepsAgreementHistoryAndRecordsRevocationTime() {
        UserTermAgreement userTermAgreement = UserTermAgreement.agree(
                USER_ID, TermAgreementType.MARKETING, NOW, NOW);

        userTermAgreement.revoke(NOW.plusSeconds(60), NOW.plusSeconds(61));

        assertThat(userTermAgreement.isAgreed()).isFalse();
        assertThat(userTermAgreement.getAgreedAt()).isEqualTo(NOW);
        assertThat(userTermAgreement.getRevokedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThat(userTermAgreement.getUpdatedAt()).isEqualTo(NOW.plusSeconds(61));
    }

    @Test
    void reagreeClearsRevocationAndUpdatesAgreementTime() {
        UserTermAgreement userTermAgreement = UserTermAgreement.agree(
                USER_ID, TermAgreementType.MARKETING, NOW, NOW);
        userTermAgreement.revoke(NOW.plusSeconds(60), NOW.plusSeconds(61));

        userTermAgreement.reagree(NOW.plusSeconds(120), NOW.plusSeconds(121));

        assertThat(userTermAgreement.isAgreed()).isTrue();
        assertThat(userTermAgreement.getAgreedAt()).isEqualTo(NOW.plusSeconds(120));
        assertThat(userTermAgreement.getRevokedAt()).isNull();
    }

    @Test
    void requiredTermsAreTermsOfServiceAndPrivacyPolicyOnly() {
        assertThat(TermAgreementType.TERMS_OF_SERVICE.isRequired()).isTrue();
        assertThat(TermAgreementType.PRIVACY_POLICY.isRequired()).isTrue();
        assertThat(TermAgreementType.MARKETING.isRequired()).isFalse();
    }
}
