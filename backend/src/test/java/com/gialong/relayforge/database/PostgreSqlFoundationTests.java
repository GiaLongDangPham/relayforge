package com.gialong.relayforge.database;

import com.gialong.relayforge.RelayForgeApplication;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = "relayforge.runtime=api",
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class PostgreSqlFoundationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_test")
            .withUsername("relayforge_test")
            .withPassword("relayforge_test");

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Test
    void providesValidPooledPostgreSqlConnectionInUtc() throws SQLException {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("PostgreSQL");
        }

        Integer serverMajor = jdbcTemplate.queryForObject(
                "select current_setting('server_version_num')::integer / 10000",
                Integer.class
        );
        String sessionTimeZone = jdbcTemplate.queryForObject("show time zone", String.class);
        String currentSchema = jdbcTemplate.queryForObject("select current_schema()", String.class);

        assertThat(serverMajor).isEqualTo(17);
        assertThat(sessionTimeZone).isEqualTo("UTC");
        assertThat(currentSchema).isEqualTo("public");
    }

    @Test
    void appliesMigrationsToTheExpectedPublicSchema() {
        var currentMigration = flyway.info().current();

        assertThat(currentMigration).isNotNull();
        assertThat(currentMigration.getVersion().getVersion()).isEqualTo("2");
        assertThat(currentMigration.getDescription()).isEqualTo("create owner accounts");

        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success",
                Integer.class
        );
        List<String> businessTables = jdbcTemplate.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_schema = 'public' and table_name <> 'flyway_schema_history' "
                        + "order by table_name",
                String.class
        );

        assertThat(successfulMigrations).isEqualTo(2);
        assertThat(businessTables).containsExactly("owner_accounts");
    }

    @Test
    void suppliesDatabaseOwnedDefaultsForAValidOwner() {
        Map<String, Object> inserted = jdbcTemplate.queryForMap(
                "insert into owner_accounts (id, login_name, password_hash) "
                        + "values (?, ?, ?) returning version, created_at, updated_at",
                UUID.randomUUID(),
                "owner_1",
                "$2a$12$valid-looking-test-hash"
        );

        assertThat(inserted.get("version")).isEqualTo(0L);
        assertThat(inserted.get("created_at")).isNotNull();
        assertThat(inserted.get("updated_at")).isEqualTo(inserted.get("created_at"));
    }

    @Test
    void requiresApplicationSuppliedOwnerId() {
        Map<String, Object> idMetadata = jdbcTemplate.queryForMap(
                "select is_nullable, column_default from information_schema.columns "
                        + "where table_schema = 'public' and table_name = 'owner_accounts' and column_name = 'id'"
        );

        assertThat(idMetadata.get("is_nullable")).isEqualTo("NO");
        assertThat(idMetadata.get("column_default")).isNull();
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into owner_accounts (login_name, password_hash) values (?, ?)",
                "missing_id_owner",
                "$2a$12$valid-looking-test-hash"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateCanonicalLoginWithoutReplacingExistingHash() {
        String loginName = "duplicate_owner";
        String originalHash = "$2a$12$original-test-hash";

        insertOwner(loginName, originalHash);

        assertThatThrownBy(() -> insertOwner(loginName, "$2a$12$replacement-test-hash"))
                .isInstanceOf(DataIntegrityViolationException.class);

        String storedHash = jdbcTemplate.queryForObject(
                "select password_hash from owner_accounts where login_name = ?",
                String.class,
                loginName
        );
        assertThat(storedHash).isEqualTo(originalHash);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Owner", " owner", "owner ", "owner@example", "-owner", "tên"})
    void rejectsNoncanonicalLoginNames(String loginName) {
        assertInvalidOwner(loginName, "$2a$12$valid-looking-test-hash", 0L);
    }

    @Test
    void rejectsOverlengthLoginName() {
        assertInvalidOwner("a".repeat(101), "$2a$12$valid-looking-test-hash", 0L);
    }

    @Test
    void rejectsInvalidPasswordHashesAndNegativeVersions() {
        assertInvalidOwner("null_hash_owner", null, 0L);
        assertInvalidOwner("empty_hash_owner", "", 0L);
        assertInvalidOwner("padded_hash_owner", "  padded-hash  ", 0L);
        assertInvalidOwner("tab_hash_owner", "\t", 0L);
        assertInvalidOwner("newline_hash_owner", "\r\n", 0L);
        assertInvalidOwner("embedded_space_hash_owner", "encoded hash", 0L);
        assertInvalidOwner("long_hash_owner", "x".repeat(256), 0L);
        assertInvalidOwner("negative_version_owner", "$2a$12$valid-looking-test-hash", -1L);
    }

    private void insertOwner(String loginName, String passwordHash) {
        jdbcTemplate.update(
                "insert into owner_accounts (id, login_name, password_hash) values (?, ?, ?)",
                UUID.randomUUID(),
                loginName,
                passwordHash
        );
    }

    private void assertInvalidOwner(String loginName, String passwordHash, long version) {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into owner_accounts (id, login_name, password_hash, version) values (?, ?, ?, ?)",
                UUID.randomUUID(),
                loginName,
                passwordHash,
                version
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

}
