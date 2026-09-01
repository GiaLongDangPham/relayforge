package com.gialong.relayforge.endpoint;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.endpoint.api.CreatedWebhookEndpoint;
import com.gialong.relayforge.endpoint.api.WebhookEndpointCatalog;
import com.gialong.relayforge.endpoint.api.WebhookEndpointDetails;
import com.gialong.relayforge.endpoint.api.WebhookEndpointPage;
import com.gialong.relayforge.endpoint.api.WebhookEndpointVersionConflictException;
import com.gialong.relayforge.endpoint.application.EncryptedEndpointSecret;
import com.gialong.relayforge.endpoint.application.SecretCipher;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
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
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
class WebhookEndpointIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_endpoint_test")
            .withUsername("relayforge_endpoint_test")
            .withPassword("relayforge_endpoint_test");

    @Autowired
    private OwnerBootstrap ownerBootstrap;

    @Autowired
    private ProjectCatalog projectCatalog;

    @Autowired
    private WebhookEndpointCatalog endpointCatalog;

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void managesOwnedEndpointsWithAtomicSubscriptionsAndImmutableSecret() {
        UUID firstOwnerId = bootstrap("endpoint.first.owner").ownerId();
        UUID secondOwnerId = bootstrap("endpoint.second.owner").ownerId();
        ProjectDetails firstProject = projectCatalog.create(firstOwnerId, "Payments");
        ProjectDetails secondProject = projectCatalog.create(secondOwnerId, "Operations");

        CreatedWebhookEndpoint created = endpointCatalog.create(
                firstOwnerId,
                firstProject.id(),
                " Billing receiver ",
                "http://localhost:8080/webhooks",
                List.of("invoice.failed", "invoice.paid"),
                true,
                120
        ).orElseThrow();
        WebhookEndpointDetails initial = created.endpoint();

        assertThat(initial.name()).isEqualTo("Billing receiver");
        assertThat(initial.eventTypes()).containsExactly("invoice.failed", "invoice.paid");
        assertThat(initial.enabled()).isTrue();
        assertThat(initial.minimumRetryDelaySeconds()).isEqualTo(120);
        assertThat(initial.version()).isZero();
        assertThat(created.signingSecret()).matches("whsec_[A-Za-z0-9_-]{43}");
        byte[] rawSecret = Base64.getUrlDecoder().decode(created.signingSecret().substring("whsec_".length()));
        byte[] ciphertext = jdbcTemplate.queryForObject(
                "select signing_secret_ciphertext from webhook_endpoints where id = ?",
                byte[].class,
                initial.id()
        );
        String keyReference = jdbcTemplate.queryForObject(
                "select encryption_key_reference from webhook_endpoints where id = ?",
                String.class,
                initial.id()
        );
        byte[] decrypted = secretCipher.decrypt(
                new EncryptedEndpointSecret(keyReference, ciphertext),
                firstProject.id(),
                initial.id()
        );
        try {
            assertThat(containsSequence(ciphertext, rawSecret)).isFalse();
            assertThat(decrypted).containsExactly(rawSecret);
        } finally {
            Arrays.fill(rawSecret, (byte) 0);
            Arrays.fill(decrypted, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }

        CreatedWebhookEndpoint secondEndpoint = endpointCatalog.create(
                firstOwnerId,
                firstProject.id(),
                "Refund receiver",
                "https://receiver.example/refunds",
                List.of("refund.completed"),
                false
        ).orElseThrow();
        WebhookEndpointPage firstPage = endpointCatalog.listOwned(firstOwnerId, firstProject.id(), 1, null).orElseThrow();
        WebhookEndpointPage secondPage = endpointCatalog.listOwned(
                firstOwnerId,
                firstProject.id(),
                1,
                firstPage.nextCursor()
        ).orElseThrow();
        assertThat(firstPage.items()).hasSize(1);
        assertThat(secondPage.items()).hasSize(1);
        assertThat(firstPage.items()).extracting(WebhookEndpointDetails::id)
                .doesNotContainAnyElementsOf(secondPage.items().stream().map(WebhookEndpointDetails::id).toList());
        assertThat(endpointCatalog.findOwned(secondOwnerId, firstProject.id(), initial.id())).isEmpty();
        assertThat(endpointCatalog.listOwned(secondOwnerId, firstProject.id(), 20, "invalid-cursor")).isEmpty();
        assertThat(endpointCatalog.listOwned(firstOwnerId, secondProject.id(), 20, null)).isEmpty();

        WebhookEndpointDetails replaced = endpointCatalog.replaceConfiguration(
                firstOwnerId,
                firstProject.id(),
                initial.id(),
                "Updated receiver",
                "https://receiver.example/updated",
                List.of("invoice.paid"),
                300,
                0
        ).orElseThrow();
        assertThat(replaced.name()).isEqualTo("Updated receiver");
        assertThat(replaced.destinationUrl()).isEqualTo("https://receiver.example/updated");
        assertThat(replaced.eventTypes()).containsExactly("invoice.paid");
        assertThat(replaced.enabled()).isTrue();
        assertThat(replaced.minimumRetryDelaySeconds()).isEqualTo(300);
        assertThat(replaced.version()).isEqualTo(1);
        byte[] rawSecretAfterConfigurationChange = Base64.getUrlDecoder()
                .decode(created.signingSecret().substring("whsec_".length()));
        byte[] ciphertextAfterConfigurationChange = jdbcTemplate.queryForObject(
                "select signing_secret_ciphertext from webhook_endpoints where id = ?",
                byte[].class,
                initial.id()
        );
        byte[] decryptedAfterConfigurationChange = secretCipher.decrypt(
                new EncryptedEndpointSecret(keyReference, ciphertextAfterConfigurationChange),
                firstProject.id(),
                initial.id()
        );
        try {
            assertThat(decryptedAfterConfigurationChange).containsExactly(rawSecretAfterConfigurationChange);
        } finally {
            Arrays.fill(rawSecretAfterConfigurationChange, (byte) 0);
            Arrays.fill(ciphertextAfterConfigurationChange, (byte) 0);
            Arrays.fill(decryptedAfterConfigurationChange, (byte) 0);
        }
        WebhookEndpointDetails subscriptionsOnly = endpointCatalog.replaceConfiguration(
                firstOwnerId,
                firstProject.id(),
                initial.id(),
                "Updated receiver",
                "https://receiver.example/updated",
                List.of("invoice.failed"),
                1
        ).orElseThrow();
        assertThat(subscriptionsOnly.eventTypes()).containsExactly("invoice.failed");
        assertThat(subscriptionsOnly.version()).isEqualTo(2);
        assertThatThrownBy(() -> endpointCatalog.replaceConfiguration(
                firstOwnerId,
                firstProject.id(),
                initial.id(),
                "Stale",
                "https://receiver.example/stale",
                List.of("invoice.paid"),
                1
        )).isInstanceOf(WebhookEndpointVersionConflictException.class);
        assertThatThrownBy(() -> endpointCatalog.replaceConfiguration(
                firstOwnerId,
                firstProject.id(),
                initial.id(),
                "Invalid",
                "https://receiver.example/invalid",
                List.of(),
                2
        )).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from webhook_endpoints where project_id = ?",
                Integer.class,
                firstProject.id()
        )).isEqualTo(2);
        assertThat(jdbcTemplate.queryForList(
                "select event_type from endpoint_subscriptions where endpoint_id = ? order by event_type",
                String.class,
                initial.id()
        )).containsExactly("invoice.failed");

        WebhookEndpointDetails disabled = endpointCatalog.setEnabled(
                firstOwnerId, firstProject.id(), initial.id(), false, 2
        ).orElseThrow();
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.version()).isEqualTo(3);
        assertThat(endpointCatalog.setEnabled(firstOwnerId, firstProject.id(), initial.id(), false, 0))
                .contains(disabled);
        assertThatThrownBy(() -> endpointCatalog.setEnabled(firstOwnerId, firstProject.id(), initial.id(), true, 2))
                .isInstanceOf(WebhookEndpointVersionConflictException.class);
        assertThat(endpointCatalog.setEnabled(firstOwnerId, firstProject.id(), initial.id(), true, 3))
                .hasValueSatisfying(enabled -> {
                    assertThat(enabled.enabled()).isTrue();
                    assertThat(enabled.version()).isEqualTo(4);
                });
        assertThat(secondEndpoint.endpoint().enabled()).isFalse();
    }

    @Test
    void letsOnlyOneConcurrentSubscriptionReplacementWinForTheSameVersion() throws Exception {
        UUID ownerId = bootstrap("endpoint.concurrent.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Concurrent endpoint project");
        WebhookEndpointDetails endpoint = endpointCatalog.create(
                ownerId,
                project.id(),
                "Concurrent receiver",
                "https://receiver.example/concurrent",
                List.of("order.created"),
                true
        ).orElseThrow().endpoint();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> replaceFromConcurrentContender(
                    ready, start, ownerId, project.id(), endpoint.id(), List.of("order.confirmed")
            ));
            Future<Boolean> second = executor.submit(() -> replaceFromConcurrentContender(
                    ready, start, ownerId, project.id(), endpoint.id(), List.of("order.cancelled")
            ));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        WebhookEndpointDetails winner = endpointCatalog.findOwned(ownerId, project.id(), endpoint.id()).orElseThrow();
        assertThat(winner.version()).isEqualTo(1);
        assertThat(winner.eventTypes()).isIn(List.of("order.confirmed"), List.of("order.cancelled"));
    }

    private boolean replaceFromConcurrentContender(
            CountDownLatch ready,
            CountDownLatch start,
            UUID ownerId,
            UUID projectId,
            UUID endpointId,
            List<String> eventTypes
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent test did not start");
        }
        try {
            endpointCatalog.replaceConfiguration(
                    ownerId,
                    projectId,
                    endpointId,
                    "Concurrent receiver",
                    "https://receiver.example/concurrent",
                    eventTypes,
                    0
            ).orElseThrow();
            return true;
        } catch (WebhookEndpointVersionConflictException exception) {
            return false;
        }
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "webhook-endpoint-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static boolean containsSequence(byte[] haystack, byte[] needle) {
        for (int index = 0; index <= haystack.length - needle.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < needle.length; offset++) {
                if (haystack[index + offset] != needle[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
