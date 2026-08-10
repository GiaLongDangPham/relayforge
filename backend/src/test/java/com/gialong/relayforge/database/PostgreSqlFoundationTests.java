package com.gialong.relayforge.database;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.identity.persistence.OwnerAccountEntity;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

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

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

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

    @Test
    void persistsAndReloadsOwnerThroughOnePersistenceContext() {
        UUID ownerId = UUID.randomUUID();

        OwnerAccountEntity reloaded = inTransaction(() -> {
            OwnerAccountEntity owner = OwnerAccountEntity.create(
                    ownerId,
                    "jpa_owner",
                    "$2a$12$jpa-mapping-test-hash"
            );
            assertThat(owner.version()).isNull();

            entityManager.persist(owner);
            assertThat(entityManager.contains(owner)).isTrue();
            assertThat(entityManager.find(OwnerAccountEntity.class, ownerId)).isSameAs(owner);

            entityManager.flush();
            assertThat(owner.version()).isZero();
            assertThat(owner.createdAt()).isNotNull();
            assertThat(owner.updatedAt()).isEqualTo(owner.createdAt());

            entityManager.clear();
            assertThat(entityManager.contains(owner)).isFalse();
            return entityManager.find(OwnerAccountEntity.class, ownerId);
        });

        assertThat(reloaded.id()).isEqualTo(ownerId);
        assertThat(reloaded.loginName()).isEqualTo("jpa_owner");
        assertThat(reloaded.passwordHash()).isEqualTo("$2a$12$jpa-mapping-test-hash");
        assertThat(reloaded.version()).isZero();
        assertThat(reloaded.createdAt()).isNotNull();
        assertThat(reloaded.updatedAt()).isNotNull();
    }

    @Test
    void dirtyCheckingUpdatesStateAndIncrementsVersionOnce() {
        UUID ownerId = persistJpaOwner("dirty_check_owner", "$2a$12$before-dirty-check");
        OwnerAccountEntity beforeUpdate = inTransaction(
                () -> entityManager.find(OwnerAccountEntity.class, ownerId)
        );

        OwnerAccountEntity afterUpdate = inTransaction(() -> {
            OwnerAccountEntity managed = entityManager.find(OwnerAccountEntity.class, ownerId);
            managed.changePasswordHash("$2a$12$after-dirty-check");
            entityManager.flush();
            return managed;
        });

        assertThat(afterUpdate.version()).isEqualTo(1L);
        assertThat(afterUpdate.updatedAt()).isAfterOrEqualTo(beforeUpdate.updatedAt());

        OwnerAccountEntity stored = inTransaction(
                () -> entityManager.find(OwnerAccountEntity.class, ownerId)
        );
        assertThat(stored.passwordHash()).isEqualTo("$2a$12$after-dirty-check");
        assertThat(stored.version()).isEqualTo(1L);
    }

    @Test
    void staleDetachedRevisionCannotOverwriteWinningUpdate() {
        UUID ownerId = persistJpaOwner("optimistic_owner", "$2a$12$initial-version");
        OwnerAccountEntity stale = inTransaction(
                () -> entityManager.find(OwnerAccountEntity.class, ownerId)
        );

        inTransaction(() -> {
            OwnerAccountEntity winner = entityManager.find(OwnerAccountEntity.class, ownerId);
            winner.changePasswordHash("$2a$12$winning-version");
            entityManager.flush();
            return null;
        });

        stale.changePasswordHash("$2a$12$stale-version");
        assertThatThrownBy(() -> inTransaction(() -> {
            entityManager.merge(stale);
            entityManager.flush();
            return null;
        })).isInstanceOf(OptimisticLockException.class);

        OwnerAccountEntity stored = inTransaction(
                () -> entityManager.find(OwnerAccountEntity.class, ownerId)
        );
        assertThat(stored.passwordHash()).isEqualTo("$2a$12$winning-version");
        assertThat(stored.version()).isEqualTo(1L);
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

    private UUID persistJpaOwner(String loginName, String passwordHash) {
        UUID ownerId = UUID.randomUUID();
        inTransaction(() -> {
            entityManager.persist(OwnerAccountEntity.create(ownerId, loginName, passwordHash));
            return null;
        });
        return ownerId;
    }

    private <T> T inTransaction(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

}
