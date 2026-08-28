package com.gialong.relayforge.identity;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.identity.api.OwnerCredentialVerifier;
import com.gialong.relayforge.identity.api.VerifiedOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = "relayforge.runtime=api",
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class OwnerCredentialVerificationIntegrationTests {

    private static final String CORRECT_SECRET = "credential-verification-correct-marker";
    private static final String WRONG_SECRET = "credential-verification-wrong-marker";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_credential_test")
            .withUsername("relayforge_credential_test")
            .withPassword("relayforge_credential_test");

    @Autowired
    private OwnerBootstrap ownerBootstrap;

    @Autowired
    private OwnerCredentialVerifier credentialVerifier;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void verifiesCanonicalOwnerThroughJpaWithoutMutatingCredential(CapturedOutput output) {
        var created = bootstrap("Credential.Owner", CORRECT_SECRET);
        Map<String, Object> before = jdbcTemplate.queryForMap(
                "select password_hash, version from owner_accounts where id = ?",
                created.ownerId()
        );

        Optional<VerifiedOwner> verified = verify(" Credential.Owner ", CORRECT_SECRET);

        assertThat(verified).contains(new VerifiedOwner(created.ownerId(), "credential.owner"));
        assertThat(jdbcTemplate.queryForMap(
                "select password_hash, version from owner_accounts where id = ?",
                created.ownerId()
        )).isEqualTo(before);
        assertThat(verified.orElseThrow().toString())
                .doesNotContain((String) before.get("password_hash"))
                .doesNotContain(CORRECT_SECRET);
        assertThat(output).doesNotContain(CORRECT_SECRET);
    }

    @Test
    void wrongUnknownAndMalformedCredentialsReturnSameEmptyOutcome(CapturedOutput output) {
        bootstrap("generic.failure.owner", CORRECT_SECRET);

        Optional<VerifiedOwner> wrongPassword = verify("generic.failure.owner", WRONG_SECRET);
        Optional<VerifiedOwner> unknownLogin = verify("unknown.failure.owner", WRONG_SECRET);
        Optional<VerifiedOwner> malformedLogin = verify("invalid@owner", WRONG_SECRET);

        assertThat(wrongPassword).isEmpty();
        assertThat(unknownLogin).isEmpty();
        assertThat(malformedLogin).isEmpty();
        assertThat(output).doesNotContain(CORRECT_SECRET).doesNotContain(WRONG_SECRET);
    }

    private OwnerBootstrapResult bootstrap(String loginName, String password) {
        char[] passwordCharacters = password.toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, passwordCharacters);
        } finally {
            Arrays.fill(passwordCharacters, '\0');
        }
    }

    private Optional<VerifiedOwner> verify(String loginName, String password) {
        char[] passwordCharacters = password.toCharArray();
        try {
            return credentialVerifier.verify(loginName, passwordCharacters);
        } finally {
            Arrays.fill(passwordCharacters, '\0');
        }
    }
}
