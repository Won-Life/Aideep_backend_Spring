package com.aideep.domain.onboarding.repository;

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
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** V6 마이그레이션이 운영 aideep 스키마에 온보딩 두 테이블과 enum 타입을 기대한 계약대로 만드는지 검증한다. */
@Testcontainers
class OnboardingMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withUsername("aideep");

    private static final String USER_ID = "44444444-4444-4444-8444-444444444444";

    private static final String INSERT_TERM_AGREEMENT = """
            insert into aideep.user_term_agreements
                (user_term_agreement_id, user_id, term_type, agreed, agreed_at, created_at, updated_at)
            values ('66666666-6666-4666-8666-666666666666', '%s', 'TERMS_OF_SERVICE', true,
                    '2026-10-05T10:00:00Z', '2026-10-05T10:00:00Z', '2026-10-05T10:00:00Z')
            """.formatted(USER_ID);

    @BeforeEach
    void setUp() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
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

    @Test
    void createsOnboardingTablesWithEnumsAndConstraints() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V6__add_onboarding.sql"));

            assertThat(columnNames(statement, "user_onboarding_profiles")).containsExactly(
                    "user_onboarding_profile_id", "user_id", "usage_purpose", "meeting_platforms",
                    "completed_at", "created_at", "updated_at", "deleted_at");
            assertThat(columnNames(statement, "user_term_agreements")).containsExactly(
                    "user_term_agreement_id", "user_id", "term_type", "agreed", "agreed_at", "revoked_at",
                    "created_at", "updated_at", "deleted_at");
            assertThat(enumLabels(statement, "usage_purpose_enum")).containsExactly(
                    "TEAM_PROJECT", "SIDE_PROJECT", "STUDY_CLUB", "COMPANY_WORK", "OTHER");
            assertThat(enumLabels(statement, "term_agreement_type_enum")).containsExactly(
                    "TERMS_OF_SERVICE", "PRIVACY_POLICY", "MARKETING");

            seedUser(statement);
            statement.execute("""
                    insert into aideep.user_onboarding_profiles
                        (user_onboarding_profile_id, user_id, usage_purpose, meeting_platforms,
                         created_at, updated_at)
                    values ('55555555-5555-4555-8555-555555555555', '%s', 'TEAM_PROJECT',
                            '{ZOOM,GOOGLE_MEET}', '2026-10-05T10:00:00Z', '2026-10-05T10:00:00Z')
                    """.formatted(USER_ID));
            statement.execute(INSERT_TERM_AGREEMENT);

            // 사용자당 프로필은 한 행, 약관은 항목당 한 행만 허용한다.
            assertThatThrownBy(() -> statement.execute("""
                    insert into aideep.user_onboarding_profiles
                        (user_onboarding_profile_id, user_id, created_at, updated_at)
                    values ('77777777-7777-4777-8777-777777777777', '%s',
                            '2026-10-05T10:00:00Z', '2026-10-05T10:00:00Z')
                    """.formatted(USER_ID)))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23505");
            assertThatThrownBy(() -> statement.execute(INSERT_TERM_AGREEMENT.replace(
                    "'66666666-6666-4666-8666-666666666666'", "'88888888-8888-4888-8888-888888888888'")))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("23505");

            // 사용자가 삭제되면 온보딩 데이터도 함께 정리된다.
            statement.execute("delete from aideep.users where user_id = '%s'".formatted(USER_ID));
            assertThat(count(statement, "user_onboarding_profiles")).isZero();
            assertThat(count(statement, "user_term_agreements")).isZero();
        }
    }

    @Test
    void rejectsUnknownUsagePurposeLabel() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V6__add_onboarding.sql"));
            seedUser(statement);

            assertThatThrownBy(() -> statement.execute("""
                    insert into aideep.user_onboarding_profiles
                        (user_onboarding_profile_id, user_id, usage_purpose, created_at, updated_at)
                    values ('99999999-9999-4999-8999-999999999999', '%s', 'UNKNOWN',
                            '2026-10-05T10:00:00Z', '2026-10-05T10:00:00Z')
                    """.formatted(USER_ID)))
                    .isInstanceOf(SQLException.class)
                    .extracting(error -> ((SQLException) error).getSQLState()).isEqualTo("22P02");
        }
    }

    private void seedUser(Statement statement) throws SQLException {
        statement.execute("""
                insert into aideep.users(user_id, email, username)
                values ('%s', 'onboarding@example.com', 'onboarding')
                """.formatted(USER_ID));
    }

    private int count(Statement statement, String tableName) throws SQLException {
        try (var rows = statement.executeQuery("select count(*) from aideep." + tableName)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private List<String> columnNames(Statement statement, String tableName) throws SQLException {
        try (var columns = statement.executeQuery("""
                select column_name
                from information_schema.columns
                where table_schema = 'aideep' and table_name = '%s'
                order by ordinal_position
                """.formatted(tableName))) {
            List<String> names = new ArrayList<>();
            while (columns.next()) {
                names.add(columns.getString(1));
            }
            return names;
        }
    }

    private List<String> enumLabels(Statement statement, String typeName) throws SQLException {
        try (var labels = statement.executeQuery("""
                select e.enumlabel
                from pg_type t
                join pg_enum e on e.enumtypid = t.oid
                join pg_namespace n on n.oid = t.typnamespace
                where n.nspname = 'aideep' and t.typname = '%s'
                order by e.enumsortorder
                """.formatted(typeName))) {
            List<String> values = new ArrayList<>();
            while (labels.next()) {
                values.add(labels.getString(1));
            }
            return values;
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
