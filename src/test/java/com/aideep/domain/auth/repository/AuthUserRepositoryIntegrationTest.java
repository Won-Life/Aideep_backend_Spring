package com.aideep.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aideep.domain.auth.entity.AuthUser;
import com.aideep.domain.auth.security.UserDetail;
import com.aideep.domain.auth.service.AuthService;
import java.time.Instant;
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

@Testcontainers
@ActiveProfiles("test")
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
class AuthUserRepositoryIntegrationTest {
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
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsUserWithoutNicknameAndBuildsAuthenticationIdentity() {
        AuthUser authUser = authUserRepository.saveAndFlush(new AuthUser("no-name@example.com", "hash", NOW));

        assertThat(jdbcTemplate.queryForObject("select username from users where user_id=?", String.class,
                authUser.getId())).isNull();
        var identity = AuthService.identity(authUser);
        assertThat(identity.email()).isEqualTo("no-name@example.com");
        assertThat(identity.user_id()).isEqualTo(authUser.getId().toString());
        UserDetail userDetail = UserDetail.from(authUser, false);
        assertThat(userDetail.userId()).isEqualTo(authUser.getId());
        assertThat(userDetail.email()).isEqualTo(identity.email());
        assertThat(userDetail.createdAt()).isEqualTo(NOW);
        assertThat(userDetail.master()).isFalse();
    }
}
