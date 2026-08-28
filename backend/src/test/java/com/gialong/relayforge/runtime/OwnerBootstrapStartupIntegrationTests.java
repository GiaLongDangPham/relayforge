package com.gialong.relayforge.runtime;

import com.gialong.relayforge.RelayForgeApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class OwnerBootstrapStartupIntegrationTests {

    private static final String FIRST_SECRET = "startup-integration-first-secret";
    private static final String REPLACEMENT_SECRET = "startup-integration-replacement-secret";
    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_startup_test")
            .withUsername("relayforge_startup_test")
            .withPassword("relayforge_startup_test");

    @Test
    void firstStartupCreatesOwnerAndRestartPreservesOriginalHash(CapturedOutput output) {
        String originalHash;
        try (ConfigurableApplicationContext firstContext = startApplication(FIRST_SECRET)) {
            JdbcTemplate jdbcTemplate = firstContext.getBean(JdbcTemplate.class);
            originalHash = jdbcTemplate.queryForObject(
                    "select password_hash from owner_accounts where login_name = 'startup.owner'",
                    String.class
            );
            assertThat(BCRYPT.matches(FIRST_SECRET, originalHash)).isTrue();
        }

        try (ConfigurableApplicationContext secondContext = startApplication(REPLACEMENT_SECRET)) {
            JdbcTemplate jdbcTemplate = secondContext.getBean(JdbcTemplate.class);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from owner_accounts where login_name = 'startup.owner'",
                    Integer.class
            )).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select password_hash from owner_accounts where login_name = 'startup.owner'",
                    String.class
            )).isEqualTo(originalHash);
            assertThat(BCRYPT.matches(REPLACEMENT_SECRET, originalHash)).isFalse();
        }

        assertThat(output).contains("outcome=CREATED")
                .contains("outcome=EXISTING")
                .doesNotContain(FIRST_SECRET)
                .doesNotContain(REPLACEMENT_SECRET)
                .doesNotContain("Startup.Owner");
    }

    private ConfigurableApplicationContext startApplication(String password) {
        return new SpringApplicationBuilder(RelayForgeApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(Map.of(
                        "relayforge.runtime", "api",
                        "server.port", "0",
                        "relayforge.bootstrap.owner.enabled", "true",
                        OwnerBootstrapStartupRunner.LOGIN_PROPERTY, " Startup.Owner ",
                        OwnerBootstrapStartupRunner.PASSWORD_PROPERTY, password,
                        "spring.datasource.url", POSTGRES.getJdbcUrl(),
                        "spring.datasource.username", POSTGRES.getUsername(),
                        "spring.datasource.password", POSTGRES.getPassword()
                ))
                .run();
    }
}
