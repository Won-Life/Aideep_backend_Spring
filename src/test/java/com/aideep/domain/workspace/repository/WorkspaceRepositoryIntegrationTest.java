package com.aideep.domain.workspace.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.aideep.domain.workspace.entity.UserWorkspace;
import com.aideep.domain.workspace.entity.Workspace;
import com.aideep.domain.workspace.entity.WorkspaceRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

@Testcontainers
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=false"})
class WorkspaceRepositoryIntegrationTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private WorkspaceRepository workspaceRepository;

    @Autowired
    private UserWorkspaceRepository userWorkspaceRepository;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("truncate users_workspaces, oauth_accounts, users, workspaces cascade");
        jdbcTemplate.update("insert into users(user_id,email,username) values (?,?,?)", USER_ID,
                "workspace@example.com", "workspace-user");
    }

    @Test
    void persistsWorkspaceAndMembershipUsingLegacyColumns() {
        Workspace workspace = workspaceRepository.saveAndFlush(new Workspace("개발 팀", NOW));
        UserWorkspace userWorkspace = new UserWorkspace(USER_ID, workspace.getId(), WorkspaceRole.EDITOR, NOW);

        userWorkspace = userWorkspaceRepository.saveAndFlush(userWorkspace);

        assertThat(jdbcTemplate.queryForObject(
                "select title from workspaces where workspace_id=?", String.class, workspace.getId()))
                .isEqualTo("개발 팀");
        assertThat(jdbcTemplate.queryForObject(
                "select role::text from users_workspaces where user_id=? and workspace_id=?",
                String.class, USER_ID, workspace.getId())).isEqualTo("EDITOR");
        assertThat(userWorkspaceRepository.findByIdUserIdAndIdWorkspaceIdAndDeletedAtIsNull(
                USER_ID, workspace.getId())).contains(userWorkspace);
    }

    @Test
    void excludesSoftDeletedMembership() {
        Workspace workspace = workspaceRepository.saveAndFlush(Workspace.untitled(NOW));
        UserWorkspace userWorkspace = new UserWorkspace(USER_ID, workspace.getId(), WorkspaceRole.VIEWER, NOW);
        userWorkspace.remove(NOW.plusSeconds(60));
        userWorkspaceRepository.saveAndFlush(userWorkspace);

        assertThat(userWorkspaceRepository.findByIdUserIdAndIdWorkspaceIdAndDeletedAtIsNull(
                USER_ID, workspace.getId())).isEmpty();
    }
}
