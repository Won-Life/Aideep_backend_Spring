package com.aideep.domain.node.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

@Testcontainers
class ProcessedNodeEventMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withUsername("aideep");

    private static final String INSERT_EVENT = """
            insert into aideep.processed_node_events
                (event_id, event_type, workspace_id, occurred_at, processed_at, created_at, updated_at)
            values ('11111111-1111-4111-8111-111111111111', 'NODE_CREATE_REQUESTED',
                    '22222222-2222-4222-8222-222222222222',
                    '2026-09-21T03:30:00Z', '2026-09-21T03:30:00Z',
                    '2026-09-21T03:30:00Z', '2026-09-21T03:30:00Z')
            """;

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            statement.execute("drop schema if exists aideep cascade");
            statement.execute("create schema aideep");
        }
    }

    @Test
    void createsTableInAideepWithExpectedColumnsAndConstraints() throws SQLException {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            migrate(connection);
            try (var columns = statement.executeQuery("""
                    select column_name, data_type, is_nullable, column_default,
                           character_maximum_length, datetime_precision
                    from information_schema.columns
                    where table_schema = 'aideep' and table_name = 'processed_node_events'
                    order by ordinal_position
                    """)) {
                String[] names = {"event_id", "created_at", "deleted_at", "updated_at", "event_type",
                        "occurred_at", "processed_at", "workspace_id"};
                for (String name : names) {
                    assertThat(columns.next()).isTrue();
                    assertThat(columns.getString("column_name")).isEqualTo(name);
                    assertThat(columns.getString("column_default")).isNull();
                    assertThat(columns.getString("is_nullable"))
                            .isEqualTo(name.equals("deleted_at") ? "YES" : "NO");
                    if (name.endsWith("_at")) {
                        assertThat(columns.getString("data_type")).isEqualTo("timestamp with time zone");
                        assertThat(columns.getInt("datetime_precision")).isEqualTo(6);
                    } else if (name.equals("event_type")) {
                        assertThat(columns.getString("data_type")).isEqualTo("character varying");
                        assertThat(columns.getInt("character_maximum_length")).isEqualTo(100);
                    } else {
                        assertThat(columns.getString("data_type")).isEqualTo("uuid");
                    }
                }
                assertThat(columns.next()).isFalse();
            }
            statement.execute(INSERT_EVENT);
            assertThatThrownBy(() -> statement.execute(INSERT_EVENT))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23505");
            assertThatThrownBy(() -> statement.execute(
                    "update aideep.processed_node_events set event_type = null"))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23502");
            try (var result = statement.executeQuery("select to_regclass('public.processed_node_events')")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isNull();
            }
        }
    }

    @Test
    void preservesExistingLocalTableAndEventOnRepeatedExecution() throws SQLException {
        try (Connection connection = connect(); var statement = connection.createStatement()) {
            migrate(connection);
            statement.execute(INSERT_EVENT);
            migrate(connection);
            try (var result = statement.executeQuery("select count(*) from aideep.processed_node_events")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(1);
            }
        }
    }

    private void migrate(Connection connection) {
        ScriptUtils.executeSqlScript(connection,
                new ClassPathResource("db/migration/V2__add_processed_node_events.sql"));
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
