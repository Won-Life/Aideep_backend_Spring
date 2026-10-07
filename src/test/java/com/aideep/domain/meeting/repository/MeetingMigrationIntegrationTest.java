package com.aideep.domain.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** V5 마이그레이션이 운영 aideep 스키마에 meetings 테이블과 enum 타입을 기대한 계약대로 만드는지 검증한다. */
@Testcontainers
class MeetingMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withUsername("aideep");

    private static final String INSERT_MEETING = """
            insert into aideep.meetings
                (meeting_id, workspace_id, node_id, user_id, bot_id, meeting_url, bot_type,
                 status, created_at, updated_at)
            values ('66666666-6666-4666-8666-666666666666', '22222222-2222-4222-8222-222222222222',
                    '33333333-3333-4333-8333-333333333333', '44444444-4444-4444-8444-444444444444',
                    '55555555-5555-4555-8555-555555555555', 'https://meet.test/abc', 'GOOGLE',
                    'REQUESTED', '2026-10-05T10:00:00Z', '2026-10-05T10:00:00Z')
            """;

    private static final String INSERT_SECOND_MEETING_SAME_URL = """
            insert into aideep.meetings
                (meeting_id, workspace_id, node_id, user_id, meeting_url, bot_type,
                 status, created_at, updated_at)
            values ('99999999-9999-4999-8999-999999999999', '22222222-2222-4222-8222-222222222222',
                    '33333333-3333-4333-8333-333333333333', '44444444-4444-4444-8444-444444444444',
                    'https://meet.test/abc', 'GOOGLE',
                    'REQUESTED', '2026-10-05T11:00:00Z', '2026-10-05T11:00:00Z')
            """;

    private int countByUrl(java.sql.Statement statement, String meetingUrl) throws SQLException {
        try (var rows = statement.executeQuery(
                "select count(*) from aideep.meetings where meeting_url = '" + meetingUrl + "'")) {
            rows.next();
            return rows.getInt(1);
        }
    }

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
        }
    }

    /**
     * V8의 부분 unique 인덱스가 진행 중인 같은 링크만 막고 종료된 회의는 허용하는지 검증한다.
     */
    @Test
    void createsPartialUniqueIndexOnActiveMeetingUrl() throws SQLException {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            migrate(connection);
            seedReferences(statement);
            statement.execute(INSERT_MEETING);

            assertThatThrownBy(() -> statement.execute(INSERT_SECOND_MEETING_SAME_URL))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23505");

            statement.execute("update aideep.meetings set status = 'DONE' where meeting_url = 'https://meet.test/abc'");
            statement.execute(INSERT_SECOND_MEETING_SAME_URL);
            assertThat(countByUrl(statement, "https://meet.test/abc")).isEqualTo(2);
        }
    }

    @Test
    void createsMeetingsTableWithEnumsAndForeignKeys() throws SQLException {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            migrate(connection);

            try (var columns = statement.executeQuery("""
                    select column_name, data_type, udt_name, is_nullable
                    from information_schema.columns
                    where table_schema = 'aideep' and table_name = 'meetings'
                    order by ordinal_position
                    """)) {
                String[] expected = {"meeting_id", "workspace_id", "node_id", "user_id", "bot_id", "meeting_url",
                        "bot_type", "status", "status_sub_code", "started_at", "ended_at",
                        "last_event_at", "created_at", "updated_at", "deleted_at"};
                for (String name : expected) {
                    assertThat(columns.next()).as("컬럼 %s", name).isTrue();
                    assertThat(columns.getString("column_name")).isEqualTo(name);
                }
                assertThat(columns.next()).isFalse();
            }
            assertThat(enumLabels(statement, "meeting_status_enum"))
                    .containsExactly("REQUESTED", "JOINING", "WAITING_ROOM", "IN_CALL_NOT_RECORDING", "RECORDING",
                            "CALL_ENDED", "DONE", "FAILED");
            assertThat(enumLabels(statement, "bot_type_enum")).containsExactly("ZOOM", "GOOGLE", "DISCORD");

            seedReferences(statement);
            statement.execute(INSERT_MEETING);
            assertThatThrownBy(() -> statement.execute(INSERT_MEETING))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23505");
            assertThatThrownBy(() -> statement.execute("""
                    insert into aideep.meetings
                        (meeting_id, workspace_id, node_id, user_id, meeting_url, bot_type, status,
                         created_at, updated_at)
                    values ('77777777-7777-4777-8777-777777777777', '22222222-2222-4222-8222-222222222222',
                            '88888888-8888-4888-8888-888888888888', '44444444-4444-4444-8444-444444444444',
                            'https://meet.test/x', 'ZOOM', 'REQUESTED',
                            '2026-10-05T10:00:00Z', '2026-10-05T10:00:00Z')
                    """))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23503");
        }
    }

    private java.util.List<String> enumLabels(java.sql.Statement statement, String typeName) throws SQLException {
        try (var labels = statement.executeQuery("""
                select e.enumlabel
                from pg_type t
                join pg_enum e on e.enumtypid = t.oid
                join pg_namespace n on n.oid = t.typnamespace
                where n.nspname = 'aideep' and t.typname = '%s'
                order by e.enumsortorder
                """.formatted(typeName))) {
            java.util.List<String> values = new java.util.ArrayList<>();
            while (labels.next()) {
                values.add(labels.getString(1));
            }
            return values;
        }
    }

    private void seedReferences(java.sql.Statement statement) throws SQLException {
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
    }

    private void migrate(Connection connection) {
        ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V5__add_meetings.sql"));
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V8__add_meetings_active_url_unique.sql"));
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
