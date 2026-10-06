package com.aideep.domain.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** V7이 meetings의 user_id FK를 restrict에서 cascade로 바꿔 계정 하드 삭제를 막지 않는지 검증한다. */
@Testcontainers
class UserDeletionMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withUsername("aideep");

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists aideep cascade");
            statement.execute("create schema aideep");
            // V1 베이스라인은 기존 운영 DB에 이미 있던 enum 타입과 확장을 전제로 한다.
            statement.execute("create extension if not exists pg_trgm");
            statement.execute("create type file_status_enum as enum ('PENDING', 'ACTIVE', 'DELETED')");
            statement.execute("create type node_type_enum as enum ('PROJECT', 'DATA', 'RESOURCE', 'ARCHIVE')");
            statement.execute("create type workspace_role_enum as enum ('OWNER', 'EDITOR', 'VIEWER')");
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V1__baseline.sql"));
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V5__add_meetings.sql"));
        }
    }

    @Test
    void cascadesMeetingsWhenUserIsDeleted() throws SQLException {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("db/migration/V7__cascade_meetings_on_user_delete.sql"));

            try (var rule = statement.executeQuery("""
                    select confdeltype
                    from pg_constraint
                    where conname = 'meetings_user_id_fkey'
                    """)) {
                assertThat(rule.next()).isTrue();
                assertThat(rule.getString(1)).as("c = ON DELETE CASCADE").isEqualTo("c");
            }

            statement.execute("""
                    insert into aideep.workspaces(workspace_id, title)
                    values ('22222222-2222-4222-8222-222222222222', 'meeting')
                    """);
            statement.execute("""
                    insert into aideep.users(user_id, email, username)
                    values ('44444444-4444-4444-8444-444444444444', 'host@example.com', 'host')
                    """);
            statement.execute("""
                    insert into aideep.nodes(node_id, workspace_id, title)
                    values ('33333333-3333-4333-8333-333333333333', '22222222-2222-4222-8222-222222222222', '회의 노드')
                    """);
            statement.execute("""
                    insert into aideep.meetings
                        (meeting_id, workspace_id, node_id, user_id, meeting_url, bot_type, status,
                         created_at, updated_at)
                    values ('66666666-6666-4666-8666-666666666666', '22222222-2222-4222-8222-222222222222',
                            '33333333-3333-4333-8333-333333333333', '44444444-4444-4444-8444-444444444444',
                            'https://meet.test/abc', 'GOOGLE', 'REQUESTED',
                            '2026-10-05T10:00:00Z', '2026-10-05T10:00:00Z')
                    """);

            statement.execute("delete from aideep.users where user_id = '44444444-4444-4444-8444-444444444444'");

            try (var remaining = statement.executeQuery("select count(*) from aideep.meetings")) {
                assertThat(remaining.next()).isTrue();
                assertThat(remaining.getLong(1)).isZero();
            }
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
