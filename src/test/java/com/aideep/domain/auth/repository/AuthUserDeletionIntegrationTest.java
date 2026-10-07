package com.aideep.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.entity.OAuthAccount;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

/** 계정 하드 삭제가 users를 참조하는 모든 행을 FK cascade로 함께 지우는지 검증한다. */
@Testcontainers
@ActiveProfiles("test")
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
class AuthUserDeletionIntegrationTest {
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withInitScript("global/aideep-schema.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry dynamicPropertyRegistry) {
        dynamicPropertyRegistry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        dynamicPropertyRegistry.add("spring.datasource.username", POSTGRES::getUsername);
        dynamicPropertyRegistry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private AuthUserRepository authUserRepository;

    @Autowired
    private OAuthAccountRepository oAuthAccountRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long count(String table, UUID userId) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where user_id=?", Long.class, userId);
    }

    @Test
    void removesOAuthAccountWorkspaceMembershipMeetingAndOnboardingRows() {
        AuthUser user = authUserRepository.saveAndFlush(new AuthUser("leaving@example.com", "hash", NOW));
        UUID userId = user.getId();
        oAuthAccountRepository.saveAndFlush(new OAuthAccount(userId, "google", "google-123", "leaving@example.com",
                NOW));
        UUID workspaceId = UUID.randomUUID();
        jdbcTemplate.update("insert into workspaces (workspace_id, title) values (?, ?)", workspaceId, "워크스페이스");
        jdbcTemplate.update(
                "insert into users_workspaces (user_id, workspace_id, role) values (?, ?, cast(? as workspace_role_enum))",
                userId, workspaceId, "EDITOR");
        UUID nodeId = UUID.randomUUID();
        jdbcTemplate.update("insert into nodes (node_id, workspace_id) values (?, ?)", nodeId, workspaceId);
        jdbcTemplate.update("""
                insert into meetings (meeting_id, workspace_id, node_id, user_id, meeting_url, bot_type,
                                      created_at, updated_at)
                values (?, ?, ?, ?, ?, cast(? as bot_type_enum), now(), now())
                """, UUID.randomUUID(), workspaceId, nodeId, userId, "https://meet.example.com/abc", "ZOOM");
        jdbcTemplate.update("""
                insert into user_onboarding_profiles (user_onboarding_profile_id, user_id, created_at, updated_at)
                values (?, ?, now(), now())
                """, UUID.randomUUID(), userId);
        jdbcTemplate.update("""
                insert into user_term_agreements (user_term_agreement_id, user_id, term_type, agreed,
                                                  created_at, updated_at)
                values (?, ?, cast(? as term_agreement_type_enum), true, now(), now())
                """, UUID.randomUUID(), userId, "TERMS_OF_SERVICE");

        authUserRepository.delete(user);
        authUserRepository.flush();

        assertThat(jdbcTemplate.queryForObject("select count(*) from users where user_id=?", Long.class, userId))
                .isZero();
        assertThat(count("oauth_accounts", userId)).isZero();
        assertThat(count("users_workspaces", userId)).isZero();
        assertThat(count("meetings", userId)).as("meetings는 V7에서 restrict를 cascade로 바꿨다").isZero();
        assertThat(count("user_onboarding_profiles", userId)).isZero();
        assertThat(count("user_term_agreements", userId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from workspaces where workspace_id=?", Long.class,
                workspaceId)).as("워크스페이스 자체는 남는다").isEqualTo(1);
    }
}
