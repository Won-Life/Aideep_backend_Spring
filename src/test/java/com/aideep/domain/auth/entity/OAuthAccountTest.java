package com.aideep.domain.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class OAuthAccountTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant DELETED_AT = Instant.parse("2026-09-21T01:00:00Z");
    private static final Instant RESTORED_AT = Instant.parse("2026-09-21T02:00:00Z");

    @Test
    void createsCommonEntityFields() {
        OAuthAccount oAuthAccount = createOAuthAccount();

        assertThat(oAuthAccount.getId()).isNotNull();
        assertThat(oAuthAccount.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(oAuthAccount.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(oAuthAccount.getDeletedAt()).isNull();
    }

    @Test
    void unlinksAndRevivesAccount() {
        OAuthAccount oAuthAccount = createOAuthAccount();

        oAuthAccount.unlink(DELETED_AT);

        assertThat(oAuthAccount.getDeletedAt()).isEqualTo(DELETED_AT);
        assertThat(oAuthAccount.getUpdatedAt()).isEqualTo(DELETED_AT);

        oAuthAccount.revive("changed@example.com", RESTORED_AT);

        assertThat(oAuthAccount.getEmail()).isEqualTo("changed@example.com");
        assertThat(oAuthAccount.getDeletedAt()).isNull();
        assertThat(oAuthAccount.getUpdatedAt()).isEqualTo(RESTORED_AT);
    }

    private OAuthAccount createOAuthAccount() {
        return new OAuthAccount(UUID.fromString("11111111-1111-4111-8111-111111111111"), "google", "subject",
                "user@example.com", CREATED_AT);
    }
}
