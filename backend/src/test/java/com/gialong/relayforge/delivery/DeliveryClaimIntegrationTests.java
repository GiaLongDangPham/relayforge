package com.gialong.relayforge.delivery;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.DeliveryClaimer;
import com.gialong.relayforge.delivery.api.EventPublisher;
import com.gialong.relayforge.endpoint.api.EndpointClaimEligibilityQuery;
import com.gialong.relayforge.endpoint.api.WebhookEndpointCatalog;
import com.gialong.relayforge.endpoint.api.WebhookEndpointDetails;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = {"relayforge.runtime=worker", "relayforge.worker.lifecycle-enabled=false"},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class DeliveryClaimIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_claim_test")
            .withUsername("relayforge_claim_test")
            .withPassword("relayforge_claim_test");

    @Autowired
    private OwnerBootstrap ownerBootstrap;

    @Autowired
    private ProjectCatalog projectCatalog;

    @Autowired
    private WebhookEndpointCatalog endpointCatalog;

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private DeliveryClaimer deliveryClaimer;

    @Autowired
    private EndpointClaimEligibilityQuery endpointClaimEligibilityQuery;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void claimsEnabledWorkWithoutPausedBacklogStarvationAndFencesConcurrentWorkers() throws Exception {
        UUID ownerId = bootstrap("claim.fairness.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Claim fairness");
        List<WebhookEndpointDetails> paused = List.of(
                endpoint(ownerId, project.id(), "Paused one", true),
                endpoint(ownerId, project.id(), "Paused two", true),
                endpoint(ownerId, project.id(), "Paused three", true)
        );
        WebhookEndpointDetails enabled = endpoint(ownerId, project.id(), "Enabled", true);
        eventPublisher.publish(project.id(), "paused-backlog", "invoice.paid", "{\"invoiceId\":\"paused\"}");
        paused.forEach(endpoint -> endpointCatalog.setEnabled(
                ownerId, project.id(), endpoint.id(), false, endpoint.version()
        ).orElseThrow());

        List<ClaimedDelivery> firstClaim = deliveryClaimer.claim(1, Duration.ofSeconds(15));

        assertThat(firstClaim).singleElement().extracting(ClaimedDelivery::endpointId).isEqualTo(enabled.id());
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from deliveries where endpoint_id in (?, ?, ?) and state = 'PENDING'",
                Integer.class,
                paused.get(0).id(), paused.get(1).id(), paused.get(2).id()
        )).isEqualTo(3);

        eventPublisher.publish(project.id(), "concurrent-claim", "invoice.paid", "{\"invoiceId\":\"concurrent\"}");
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<ClaimedDelivery>> first = executor.submit(() -> concurrentClaim(ready, start));
            Future<List<ClaimedDelivery>> second = executor.submit(() -> concurrentClaim(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ClaimedDelivery> firstResult = first.get(5, TimeUnit.SECONDS);
            List<ClaimedDelivery> secondResult = second.get(5, TimeUnit.SECONDS);
            assertThat(List.of(firstResult.size(), secondResult.size())).containsExactlyInAnyOrder(0, 1);
            assertThat(firstResult.stream().map(ClaimedDelivery::claimToken)
                    .toList()).doesNotHaveDuplicates();
            assertThat(secondResult.stream().map(ClaimedDelivery::claimToken)
                    .toList()).doesNotHaveDuplicates();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void recoversExpiredPreAttemptClaimWithoutConsumingBudgetOrOverwritingANewerClaim() {
        UUID ownerId = bootstrap("claim.recovery.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Claim recovery");
        endpoint(ownerId, project.id(), "Recovery receiver", true);
        eventPublisher.publish(project.id(), "recovery-claim", "invoice.paid", "{\"invoiceId\":\"recovery\"}");

        ClaimedDelivery expired = deliveryClaimer.claim(1, Duration.ofSeconds(15)).getFirst();
        jdbcTemplate.update(
                "update deliveries set lease_expires_at = CURRENT_TIMESTAMP - interval '1 millisecond' where id = ?",
                expired.deliveryId()
        );

        assertThat(deliveryClaimer.recoverExpiredPreAttemptClaims(1)).isEqualTo(1);
        var recovered = jdbcTemplate.queryForMap(
                "select state, attempt_count, claim_token, lease_expires_at from deliveries where id = ?",
                expired.deliveryId()
        );
        assertThat(recovered).containsEntry("state", "PENDING")
                .containsEntry("claim_token", null)
                .containsEntry("lease_expires_at", null);
        assertThat(((Number) recovered.get("attempt_count")).intValue()).isZero();

        ClaimedDelivery newer = deliveryClaimer.claim(1, Duration.ofSeconds(15)).getFirst();
        assertThat(newer.claimToken()).isNotEqualTo(expired.claimToken());
        assertThat(deliveryClaimer.recoverExpiredPreAttemptClaims(1)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select claim_token from deliveries where id = ?", UUID.class, newer.deliveryId()
        )).isEqualTo(newer.claimToken());
    }

    @Test
    void concurrentDisableWaitsForTheFinalEndpointEligibilityLock() throws Exception {
        UUID ownerId = bootstrap("claim.disable.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Claim disable");
        WebhookEndpointDetails endpoint = endpoint(ownerId, project.id(), "Disable receiver", true);
        CountDownLatch eligibilityLocked = new CountDownLatch(1);
        CountDownLatch releaseEligibility = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Void> lockingTransaction = executor.submit(() -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    assertThat(endpointClaimEligibilityQuery.lockAndFindEnabledForClaim(List.of(endpoint.id())))
                            .containsExactly(endpoint.id());
                    eligibilityLocked.countDown();
                    await(releaseEligibility);
                });
                return null;
            });
            assertThat(eligibilityLocked.await(5, TimeUnit.SECONDS)).isTrue();

            Future<WebhookEndpointDetails> disable = executor.submit(() -> endpointCatalog.setEnabled(
                    ownerId, project.id(), endpoint.id(), false, endpoint.version()
            ).orElseThrow());
            Thread.sleep(150);
            assertThat(disable).isNotDone();

            releaseEligibility.countDown();
            lockingTransaction.get(5, TimeUnit.SECONDS);
            assertThat(disable.get(5, TimeUnit.SECONDS).enabled()).isFalse();
        } finally {
            releaseEligibility.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<ClaimedDelivery> concurrentClaim(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent claims did not start");
        }
        return deliveryClaimer.claim(1, Duration.ofSeconds(15));
    }

    private WebhookEndpointDetails endpoint(UUID ownerId, UUID projectId, String name, boolean enabled) {
        return endpointCatalog.create(
                ownerId,
                projectId,
                name,
                "https://" + name.toLowerCase().replace(' ', '-') + ".example/webhooks",
                List.of("invoice.paid"),
                enabled
        ).orElseThrow().endpoint();
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "delivery-claim-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("test lock was not released");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test lock was interrupted", exception);
        }
    }
}
