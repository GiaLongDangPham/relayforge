package com.gialong.relayforge.delivery;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.AttemptFinalizationResult;
import com.gialong.relayforge.delivery.api.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.DeliveryAttemptFinalizer;
import com.gialong.relayforge.delivery.api.DeliveryAttemptStarter;
import com.gialong.relayforge.delivery.api.DeliveryClaimer;
import com.gialong.relayforge.delivery.api.DispatchInstruction;
import com.gialong.relayforge.delivery.api.DispatchObservation;
import com.gialong.relayforge.delivery.api.EventPublisher;
import com.gialong.relayforge.endpoint.api.EndpointClaimEligibilityQuery;
import com.gialong.relayforge.endpoint.api.WebhookEndpointCatalog;
import com.gialong.relayforge.endpoint.api.WebhookEndpointDetails;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import org.junit.jupiter.api.AfterEach;
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
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
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
    private DeliveryAttemptStarter deliveryAttemptStarter;

    @Autowired
    private DeliveryAttemptFinalizer deliveryAttemptFinalizer;

    @Autowired
    private EndpointClaimEligibilityQuery endpointClaimEligibilityQuery;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearDeliveryFixture() {
        jdbcTemplate.update("delete from attempt_late_diagnostics");
        jdbcTemplate.update("delete from delivery_attempts");
        jdbcTemplate.update("delete from replay_requests");
        jdbcTemplate.update("delete from deliveries");
        jdbcTemplate.update("delete from endpoint_subscriptions");
        jdbcTemplate.update("delete from webhook_endpoints");
        jdbcTemplate.update("delete from events");
        jdbcTemplate.update("delete from project_api_keys");
        jdbcTemplate.update("delete from projects");
        jdbcTemplate.update("delete from owner_accounts");
    }

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
    void sharesTheFirstClaimWaveWithTheSmallHealthyBacklog() {
        UUID ownerId = bootstrap("claim.noisy-neighbor.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Noisy-neighbor claim baseline");
        WebhookEndpointDetails noisyEndpoint = endpoint(
                ownerId,
                project.id(),
                "Slow backlog receiver",
                "noisy-neighbor.slow"
        );
        WebhookEndpointDetails healthyEndpoint = endpoint(
                ownerId,
                project.id(),
                "Healthy receiver",
                "noisy-neighbor.healthy"
        );

        for (int index = 0; index < 32; index++) {
            eventPublisher.publish(
                    project.id(),
                    "noisy-baseline-slow-" + index,
                    "noisy-neighbor.slow",
                    "{\"sequence\":" + index + "}"
            );
        }
        for (int index = 0; index < 2; index++) {
            eventPublisher.publish(
                    project.id(),
                    "noisy-baseline-healthy-" + index,
                    "noisy-neighbor.healthy",
                    "{\"sequence\":" + index + "}"
            );
        }
        jdbcTemplate.update(
                "update deliveries set due_at = CURRENT_TIMESTAMP - interval '60 seconds' where endpoint_id = ?",
                noisyEndpoint.id()
        );
        jdbcTemplate.update(
                "update deliveries set due_at = CURRENT_TIMESTAMP - interval '30 seconds' where endpoint_id = ?",
                healthyEndpoint.id()
        );

        List<ClaimedDelivery> firstWave = deliveryClaimer.claim(8, Duration.ofSeconds(15));

        assertThat(firstWave).hasSize(8);
        assertThat(firstWave.stream().filter(claim -> claim.endpointId().equals(noisyEndpoint.id()))).hasSize(6);
        assertThat(firstWave.stream().filter(claim -> claim.endpointId().equals(healthyEndpoint.id()))).hasSize(2);
    }

    @Test
    void splitsDeepBacklogsEvenlyAcrossTwoEndpoints() {
        UUID ownerId = bootstrap("claim.deep-backlog.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Deep backlog fairness");
        WebhookEndpointDetails firstEndpoint = endpoint(ownerId, project.id(), "First receiver", "deep-backlog.first");
        WebhookEndpointDetails secondEndpoint = endpoint(ownerId, project.id(), "Second receiver", "deep-backlog.second");

        for (int index = 0; index < 8; index++) {
            eventPublisher.publish(project.id(), "deep-first-" + index, "deep-backlog.first", "{\"sequence\":" + index + "}");
            eventPublisher.publish(project.id(), "deep-second-" + index, "deep-backlog.second", "{\"sequence\":" + index + "}");
        }
        jdbcTemplate.update(
                "update deliveries set due_at = CURRENT_TIMESTAMP - interval '60 seconds' where endpoint_id = ?",
                firstEndpoint.id()
        );
        jdbcTemplate.update(
                "update deliveries set due_at = CURRENT_TIMESTAMP - interval '30 seconds' where endpoint_id = ?",
                secondEndpoint.id()
        );

        List<ClaimedDelivery> claims = deliveryClaimer.claim(8, Duration.ofSeconds(15));

        assertThat(claims).hasSize(8);
        assertThat(claims.stream().filter(claim -> claim.endpointId().equals(firstEndpoint.id()))).hasSize(4);
        assertThat(claims.stream().filter(claim -> claim.endpointId().equals(secondEndpoint.id()))).hasSize(4);
    }

    @Test
    void concurrentWorkersMakeProgressForBothDeepBacklogsWithoutDuplicateClaims() throws Exception {
        UUID ownerId = bootstrap("claim.concurrent-fair.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Concurrent fair claims");
        WebhookEndpointDetails firstEndpoint = endpoint(ownerId, project.id(), "First concurrent receiver", "concurrent.first");
        WebhookEndpointDetails secondEndpoint = endpoint(ownerId, project.id(), "Second concurrent receiver", "concurrent.second");

        for (int index = 0; index < 8; index++) {
            eventPublisher.publish(project.id(), "concurrent-first-" + index, "concurrent.first", "{\"sequence\":" + index + "}");
            eventPublisher.publish(project.id(), "concurrent-second-" + index, "concurrent.second", "{\"sequence\":" + index + "}");
        }
        jdbcTemplate.update(
                "update deliveries set due_at = CURRENT_TIMESTAMP - interval '60 seconds' where endpoint_id = ?",
                firstEndpoint.id()
        );
        jdbcTemplate.update(
                "update deliveries set due_at = CURRENT_TIMESTAMP - interval '30 seconds' where endpoint_id = ?",
                secondEndpoint.id()
        );

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<ClaimedDelivery>> first = executor.submit(() -> concurrentClaim(ready, start, 4));
            Future<List<ClaimedDelivery>> second = executor.submit(() -> concurrentClaim(ready, start, 4));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ClaimedDelivery> claims = new java.util.ArrayList<>();
            claims.addAll(first.get(5, TimeUnit.SECONDS));
            claims.addAll(second.get(5, TimeUnit.SECONDS));

            assertThat(claims).hasSize(8);
            assertThat(claims).extracting(ClaimedDelivery::deliveryId).doesNotHaveDuplicates();
            assertThat(claims).extracting(ClaimedDelivery::claimToken).doesNotHaveDuplicates();
            assertThat(claims.stream().filter(claim -> claim.endpointId().equals(firstEndpoint.id()))).isNotEmpty();
            assertThat(claims.stream().filter(claim -> claim.endpointId().equals(secondEndpoint.id()))).isNotEmpty();
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void letsOneEndpointUseEveryFreePermitWhenItIsTheOnlyBacklog() {
        UUID ownerId = bootstrap("claim.burst.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Burst claim capacity");
        WebhookEndpointDetails endpoint = endpoint(ownerId, project.id(), "Burst receiver", "burst.event");

        for (int index = 0; index < 8; index++) {
            eventPublisher.publish(project.id(), "burst-" + index, "burst.event", "{\"sequence\":" + index + "}");
        }

        List<ClaimedDelivery> claims = deliveryClaimer.claim(8, Duration.ofSeconds(15));

        assertThat(claims)
                .hasSize(8)
                .extracting(ClaimedDelivery::endpointId)
                .containsOnly(endpoint.id());
    }

    @Test
    void givesTheNextFreeClaimToANewlyBackloggedEndpoint() {
        UUID ownerId = bootstrap("claim.new-backlog.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "New backlog priority");
        WebhookEndpointDetails busyEndpoint = endpoint(ownerId, project.id(), "Busy receiver", "busy.event");
        WebhookEndpointDetails newEndpoint = endpoint(ownerId, project.id(), "New receiver", "new.event");

        for (int index = 0; index < 9; index++) {
            eventPublisher.publish(project.id(), "busy-" + index, "busy.event", "{\"sequence\":" + index + "}");
        }
        List<ClaimedDelivery> initialClaims = deliveryClaimer.claim(8, Duration.ofSeconds(15));
        ClaimedDelivery completedClaim = initialClaims.getFirst();
        finalizeSuccessfully(List.of(completedClaim));

        eventPublisher.publish(project.id(), "new-backlog", "new.event", "{\"sequence\":0}");

        List<ClaimedDelivery> nextClaim = deliveryClaimer.claim(1, Duration.ofSeconds(15));

        assertThat(nextClaim).singleElement().extracting(ClaimedDelivery::endpointId).isEqualTo(newEndpoint.id());
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
        return concurrentClaim(ready, start, 1);
    }

    private List<ClaimedDelivery> concurrentClaim(CountDownLatch ready, CountDownLatch start, int capacity) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent claims did not start");
        }
        return deliveryClaimer.claim(capacity, Duration.ofSeconds(15));
    }

    private WebhookEndpointDetails endpoint(UUID ownerId, UUID projectId, String name, boolean enabled) {
        return endpoint(ownerId, projectId, name, "invoice.paid", enabled);
    }

    private WebhookEndpointDetails endpoint(UUID ownerId, UUID projectId, String name, String eventType) {
        return endpoint(ownerId, projectId, name, eventType, true);
    }

    private WebhookEndpointDetails endpoint(
            UUID ownerId,
            UUID projectId,
            String name,
            String eventType,
            boolean enabled
    ) {
        return endpointCatalog.create(
                ownerId,
                projectId,
                name,
                "https://" + name.toLowerCase().replace(' ', '-') + ".example/webhooks",
                List.of(eventType),
                enabled
        ).orElseThrow().endpoint();
    }

    private void finalizeSuccessfully(List<ClaimedDelivery> claims) {
        for (ClaimedDelivery claim : claims) {
            DispatchInstruction instruction = deliveryAttemptStarter.start(claim, Duration.ofSeconds(20)).orElseThrow();
            try (DispatchObservation observation = DispatchObservation.httpResponse(
                    DispatchObservation.Outcome.SUCCEEDED,
                    204,
                    Duration.ofMillis(1),
                    new byte[0],
                    false
            )) {
                assertThat(deliveryAttemptFinalizer.finalizeAttempt(instruction, observation))
                        .isEqualTo(AttemptFinalizationResult.FINALIZED);
            }
        }
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
