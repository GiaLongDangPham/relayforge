package com.gialong.relayforge.database;

import com.gialong.relayforge.RelayForgeApplication;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

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
    void appliesTechnicalBaselineWithoutCreatingBusinessTables() {
        assertThat(flyway.info().current()).isNotNull();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.info().current().getDescription())
                .isEqualTo("verify postgresql baseline");

        Integer successfulMigrations = jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where success",
                Integer.class
        );
        Integer businessTables = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema = 'public' and table_name <> 'flyway_schema_history'",
                Integer.class
        );

        assertThat(successfulMigrations).isEqualTo(1);
        assertThat(businessTables).isZero();
    }

}
