package com.aideep.domain.auth.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.time.Instant;

class AuthUserTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-21T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-09-21T01:00:00Z");

    @Test
    void createsCommonEntityFields() {
        AuthUser authUser = new AuthUser("user@example.com", "user", "password", CREATED_AT);

        assertThat(authUser.getId()).isNotNull();
        assertThat(authUser.getCreatedAt()).isEqualTo(CREATED_AT);
        assertThat(authUser.getUpdatedAt()).isEqualTo(CREATED_AT);
        assertThat(authUser.getDeletedAt()).isNull();
    }

    @Test
    void changesPasswordAndUpdateTimestamp() {
        AuthUser authUser = new AuthUser("user@example.com", "user", "old-password", CREATED_AT);

        authUser.changePassword("new-password", UPDATED_AT);

        assertThat(authUser.getPassword()).isEqualTo("new-password");
        assertThat(authUser.getUpdatedAt()).isEqualTo(UPDATED_AT);
    }
}
