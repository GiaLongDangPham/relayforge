package com.gialong.relayforge.project;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.project.api.CreatedProjectApiKey;
import com.gialong.relayforge.project.api.ProjectApiKeyCatalog;
import com.gialong.relayforge.project.api.ProjectApiKeyDetails;
import com.gialong.relayforge.project.api.ProjectApiKeyPage;
import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import com.gialong.relayforge.project.api.PublisherApiKeyVerifier;
import com.gialong.relayforge.project.api.VerifiedPublisherProject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = "relayforge.runtime=api",
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
class ProjectApiKeyIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_project_api_key_test")
            .withUsername("relayforge_project_api_key_test")
            .withPassword("relayforge_project_api_key_test");

    @Autowired
    private OwnerBootstrap ownerBootstrap;

    @Autowired
    private ProjectCatalog projectCatalog;

    @Autowired
    private ProjectApiKeyCatalog apiKeyCatalog;

    @Autowired
    private PublisherApiKeyVerifier publisherApiKeyVerifier;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsListsRevokesAndVerifiesProjectScopedPublisherKeys() {
        UUID firstOwnerId = bootstrap("api.key.first.owner").ownerId();
        UUID secondOwnerId = bootstrap("api.key.second.owner").ownerId();
        ProjectDetails firstProject = projectCatalog.create(firstOwnerId, "Payments");
        ProjectDetails secondProject = projectCatalog.create(secondOwnerId, "Operations");

        CreatedProjectApiKey firstCreated = apiKeyCatalog.create(firstOwnerId, firstProject.id(), " Checkout Publisher ")
                .orElseThrow();
        CreatedProjectApiKey secondCreated = apiKeyCatalog.create(firstOwnerId, firstProject.id(), "Refund Publisher")
                .orElseThrow();

        assertThat(firstCreated.apiKey().displayName()).isEqualTo("Checkout Publisher");
        assertThat(firstCreated.apiKey().keyHint()).startsWith("rf_live_").hasSize(24);
        assertThat(firstCreated.rawKey()).matches("rf_live_[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}");
        assertThat(jdbcTemplate.queryForObject(
                "select octet_length(secret_digest) from project_api_keys where id = ?",
                Integer.class,
                firstCreated.apiKey().id()
        )).isEqualTo(32);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from project_api_keys where secret_digest::text = ?",
                Integer.class,
                firstCreated.rawKey()
        )).isZero();
        assertThat(publisherApiKeyVerifier.verify(firstCreated.rawKey()))
                .contains(new VerifiedPublisherProject(firstProject.id(), firstCreated.apiKey().id()));
        assertThat(publisherApiKeyVerifier.verify(withDifferentFinalCharacter(firstCreated.rawKey())))
                .isEmpty();
        assertThat(publisherApiKeyVerifier.verify("not-an-api-key")).isEmpty();

        ProjectApiKeyPage firstPage = apiKeyCatalog.listOwned(firstOwnerId, firstProject.id(), 1, null).orElseThrow();
        ProjectApiKeyPage secondPage = apiKeyCatalog.listOwned(
                firstOwnerId,
                firstProject.id(),
                1,
                firstPage.nextCursor()
        ).orElseThrow();
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(secondPage.items()).hasSize(1);
        assertThat(firstPage.items()).extracting(ProjectApiKeyDetails::id)
                .doesNotContainAnyElementsOf(secondPage.items().stream().map(ProjectApiKeyDetails::id).toList());
        assertThat(firstPage.items()).extracting(ProjectApiKeyDetails::id)
                .containsAnyOf(firstCreated.apiKey().id(), secondCreated.apiKey().id());
        assertThat(apiKeyCatalog.create(secondOwnerId, firstProject.id(), "Not allowed")).isEmpty();
        assertThat(apiKeyCatalog.listOwned(secondOwnerId, firstProject.id(), 20, null)).isEmpty();
        assertThat(apiKeyCatalog.revoke(secondOwnerId, firstProject.id(), firstCreated.apiKey().id())).isEmpty();
        assertThat(apiKeyCatalog.listOwned(firstOwnerId, secondProject.id(), 20, null)).isEmpty();

        ProjectApiKeyDetails revoked = apiKeyCatalog.revoke(firstOwnerId, firstProject.id(), firstCreated.apiKey().id())
                .orElseThrow();
        assertThat(revoked.revokedAt()).isNotNull();
        assertThat(apiKeyCatalog.revoke(firstOwnerId, firstProject.id(), firstCreated.apiKey().id()))
                .contains(revoked);
        assertThat(publisherApiKeyVerifier.verify(firstCreated.rawKey())).isEmpty();
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "project-api-key-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static String withDifferentFinalCharacter(String rawKey) {
        char finalCharacter = rawKey.charAt(rawKey.length() - 1);
        return rawKey.substring(0, rawKey.length() - 1) + (finalCharacter == 'A' ? 'B' : 'A');
    }
}
