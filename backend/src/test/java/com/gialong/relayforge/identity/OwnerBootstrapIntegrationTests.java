package com.gialong.relayforge.identity;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapOutcome;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = "relayforge.runtime=api",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class OwnerBootstrapIntegrationTests {

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_bootstrap_test")
            .withUsername("relayforge_bootstrap_test")
            .withPassword("relayforge_bootstrap_test");

    @Autowired
    private OwnerBootstrap ownerBootstrap;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsCanonicalOwnerWithCostTwelveBCryptHash() {
        String plaintextPassword = "bootstrap-secret-one";

        OwnerBootstrapResult result = bootstrap("  Owner.Name  ", plaintextPassword);

        assertThat(result.loginName()).isEqualTo("owner.name");
        assertThat(result.outcome()).isEqualTo(OwnerBootstrapOutcome.CREATED);

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "select id, login_name, password_hash, version from owner_accounts where id = ?",
                result.ownerId()
        );
        String storedHash = (String) stored.get("password_hash");

        assertThat(stored.get("login_name")).isEqualTo("owner.name");
        assertThat(stored.get("version")).isEqualTo(0L);
        assertThat(storedHash).startsWith("$2a$12$");
        assertThat(storedHash).doesNotContain(plaintextPassword);
        assertThat(BCRYPT.matches(plaintextPassword, storedHash)).isTrue();
    }

    @Test
    void repeatedBootstrapReturnsExistingOwnerWithoutReplacingHash() {
        OwnerBootstrapResult created = bootstrap("Repeat.Owner", "first-bootstrap-secret");
        String originalHash = storedHash(created.ownerId());

        OwnerBootstrapResult repeated = bootstrap(" repeat.owner ", "replacement-secret");

        assertThat(created.outcome()).isEqualTo(OwnerBootstrapOutcome.CREATED);
        assertThat(repeated.outcome()).isEqualTo(OwnerBootstrapOutcome.EXISTING);
        assertThat(repeated.ownerId()).isEqualTo(created.ownerId());
        assertThat(repeated.loginName()).isEqualTo("repeat.owner");
        assertThat(storedHash(created.ownerId())).isEqualTo(originalHash);
        assertThat(BCRYPT.matches("first-bootstrap-secret", originalHash)).isTrue();
        assertThat(BCRYPT.matches("replacement-secret", originalHash)).isFalse();
    }

    @Test
    void concurrentBootstrapAttemptsConvergeOnOneWinner() throws Exception {
        String[] loginVariants = {
                "Concurrent.Owner",
                " concurrent.owner ",
                "CONCURRENT.OWNER",
                "concurrent.owner"
        };
        String[] passwords = {
                "concurrent-secret-zero",
                "concurrent-secret-one",
                "concurrent-secret-two",
                "concurrent-secret-three"
        };
        CountDownLatch ready = new CountDownLatch(loginVariants.length);
        CountDownLatch start = new CountDownLatch(1);
        List<BootstrapAttempt> attempts = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(loginVariants.length)) {
            List<Future<BootstrapAttempt>> futures = new ArrayList<>();
            for (int index = 0; index < loginVariants.length; index++) {
                int attemptIndex = index;
                Callable<BootstrapAttempt> work = () -> {
                    ready.countDown();
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    return new BootstrapAttempt(
                            attemptIndex,
                            bootstrap(loginVariants[attemptIndex], passwords[attemptIndex])
                    );
                };
                futures.add(executor.submit(work));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<BootstrapAttempt> future : futures) {
                attempts.add(future.get(30, TimeUnit.SECONDS));
            }
        }

        assertThat(attempts).filteredOn(
                attempt -> attempt.result().outcome() == OwnerBootstrapOutcome.CREATED
        ).hasSize(1);
        assertThat(attempts).filteredOn(
                attempt -> attempt.result().outcome() == OwnerBootstrapOutcome.EXISTING
        ).hasSize(loginVariants.length - 1);

        Set<UUID> ownerIds = new HashSet<>();
        attempts.forEach(attempt -> ownerIds.add(attempt.result().ownerId()));
        assertThat(ownerIds).hasSize(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from owner_accounts where login_name = 'concurrent.owner'",
                Integer.class
        )).isEqualTo(1);

        BootstrapAttempt winner = attempts.stream()
                .filter(attempt -> attempt.result().outcome() == OwnerBootstrapOutcome.CREATED)
                .findFirst()
                .orElseThrow();
        String winnerHash = storedHash(winner.result().ownerId());

        assertThat(BCRYPT.matches(passwords[winner.index()], winnerHash)).isTrue();
        for (BootstrapAttempt loser : attempts) {
            if (loser.index() != winner.index()) {
                assertThat(BCRYPT.matches(passwords[loser.index()], winnerHash)).isFalse();
            }
        }
    }

    @Test
    void rejectsInvalidInputsWithoutCreatingOwner() {
        assertThatThrownBy(() -> bootstrap("-invalid", "valid-secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bootstrap("invalid@owner", "valid-secret"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> bootstrap("blank_password_owner", " \t\r\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ownerBootstrap.bootstrap("null_password_owner", null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from owner_accounts where login_name in "
                        + "('-invalid', 'invalid@owner', 'blank_password_owner', 'null_password_owner')",
                Integer.class
        )).isZero();
    }

    private OwnerBootstrapResult bootstrap(String loginName, String plaintextPassword) {
        char[] password = plaintextPassword.toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private String storedHash(UUID ownerId) {
        return jdbcTemplate.queryForObject(
                "select password_hash from owner_accounts where id = ?",
                String.class,
                ownerId
        );
    }

    private record BootstrapAttempt(int index, OwnerBootstrapResult result) {
    }
}
