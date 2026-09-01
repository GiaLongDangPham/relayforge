package com.gialong.relayforge.delivery;
import com.gialong.relayforge.delivery.api.processing.AttemptFinalizationResult;
import com.gialong.relayforge.delivery.api.processing.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptFinalizer;
import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptRecovery;
import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptStarter;
import com.gialong.relayforge.delivery.api.processing.DeliveryClaimer;
import com.gialong.relayforge.delivery.api.processing.DispatchInstruction;
import com.gialong.relayforge.delivery.api.processing.DispatchObservation;
import com.gialong.relayforge.delivery.api.publish.EventPublisher;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.processing.AttemptFinalizationResult;
import com.gialong.relayforge.delivery.api.processing.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptFinalizer;
import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptRecovery;
import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptStarter;
import com.gialong.relayforge.delivery.api.processing.DeliveryClaimer;
import com.gialong.relayforge.delivery.api.processing.DispatchInstruction;
import com.gialong.relayforge.delivery.api.processing.DispatchObservation;
import com.gialong.relayforge.delivery.api.publish.EventPublisher;
import com.gialong.relayforge.delivery.application.EndpointCircuitStore;
import com.gialong.relayforge.delivery.application.EndpointCircuitState;
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
class DeliveryCircuitBreakerIntegrationTests {

    private static final Duration CLAIM_LEASE = Duration.ofSeconds(15);
    private static final Duration ATTEMPT_LEASE = Duration.ofSeconds(20);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_circuit_breaker_test")
            .withUsername("relayforge_circuit_breaker_test")
            .withPassword("relayforge_circuit_breaker_test");

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
    private DeliveryAttemptStarter attemptStarter;

    @Autowired
    private DeliveryAttemptFinalizer finalizer;

    @Autowired
    private DeliveryAttemptRecovery recovery;

    @Autowired
    private EndpointCircuitStore circuitStore;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearFixture() {
        jdbcTemplate.update("delete from endpoint_circuit_breakers");
        jdbcTemplate.update("delete from attempt_late_diagnostics");
        jdbcTemplate.update("delete from delivery_attempts");
        jdbcTemplate.update("delete from replay_requests");
        jdbcTemplate.update("delete from deliveries");
        jdbcTemplate.update("delete from endpoint_subscriptions");
        jdbcTemplate.update("delete from webhook_endpoints");
        jdbcTemplate.update("delete from events");
        jdbcTemplate.update("delete from project_api_keys");
        jdbcTemplate.update("delete from project_publish_quota_usage");
        jdbcTemplate.update("delete from projects");
        jdbcTemplate.update("delete from owner_accounts");
    }

    @Test
    void opensAfterThreeReceiverFailuresAndLeavesHealthyCapacityAvailable() {
        UUID ownerId = bootstrap("circuit.threshold.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Circuit threshold");
        WebhookEndpointDetails failing = endpoint(ownerId, project.id(), "Invoice receiver", "invoice.paid");
        WebhookEndpointDetails healthy = endpoint(ownerId, project.id(), "Audit receiver", "audit.recorded");

        for (int number = 1; number <= 3; number++) {
            DispatchInstruction instruction = start(project.id(), "failing-" + number, "invoice.paid");
            try (instruction; DispatchObservation observation = retryable503()) {
                assertThat(finalizer.finalizeAttempt(instruction, observation))
                        .isEqualTo(AttemptFinalizationResult.FINALIZED);
            }
            if (number < 3) {
                jdbcTemplate.update(
                        "update deliveries set due_at = CURRENT_TIMESTAMP - interval '1 millisecond' where endpoint_id = ?",
                        failing.id()
                );
            }
        }

        assertThat(circuit(failing.id()))
                .satisfies(circuit -> {
                    assertThat(circuit.state()).isEqualTo(EndpointCircuitState.OPEN);
                    assertThat(circuit.consecutiveQualifyingFailures()).isEqualTo(3);
                    assertThat(circuit.openUntil()).isNotNull();
                });

        eventPublisher.publish(project.id(), "blocked-after-open", "invoice.paid", "{\"invoiceId\":\"blocked\"}");
        eventPublisher.publish(project.id(), "healthy-after-open", "audit.recorded", "{\"auditId\":\"healthy\"}");

        assertThat(deliveryClaimer.claim(2, CLAIM_LEASE))
                .singleElement()
                .extracting(ClaimedDelivery::endpointId)
                .isEqualTo(healthy.id());
    }

    @Test
    void createsOneFencedProbeThenClosesOrReopensItWithTheProbeOutcome() throws Exception {
        UUID ownerId = bootstrap("circuit.probe.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Circuit probe");
        WebhookEndpointDetails endpoint = endpoint(ownerId, project.id(), "Recovery receiver", "invoice.paid");
        eventPublisher.publish(project.id(), "probe-first", "invoice.paid", "{\"invoiceId\":\"one\"}");
        eventPublisher.publish(project.id(), "probe-second", "invoice.paid", "{\"invoiceId\":\"two\"}");
        openExpiredCircuit(endpoint.id());

        List<ClaimedDelivery> probes = concurrentClaims();
        assertThat(probes).singleElement();
        ClaimedDelivery successProbe = probes.getFirst();
        assertThat(circuit(endpoint.id()))
                .satisfies(circuit -> {
                    assertThat(circuit.state()).isEqualTo(EndpointCircuitState.HALF_OPEN);
                    assertThat(circuit.probeDeliveryId()).isEqualTo(successProbe.deliveryId());
                    assertThat(circuit.probeClaimToken()).isEqualTo(successProbe.claimToken());
                });
        assertThat(deliveryClaimer.claim(1, CLAIM_LEASE)).isEmpty();

        DispatchInstruction successfulInstruction = attemptStarter.start(successProbe, ATTEMPT_LEASE).orElseThrow();
        try (successfulInstruction; DispatchObservation observation = succeeded204()) {
            assertThat(finalizer.finalizeAttempt(successfulInstruction, observation))
                    .isEqualTo(AttemptFinalizationResult.FINALIZED);
        }
        assertThat(circuit(endpoint.id()))
                .satisfies(circuit -> {
                    assertThat(circuit.state()).isEqualTo(EndpointCircuitState.CLOSED);
                    assertThat(circuit.consecutiveQualifyingFailures()).isZero();
                });

        openExpiredCircuit(endpoint.id());
        ClaimedDelivery unknownProbe = deliveryClaimer.claim(1, CLAIM_LEASE).getFirst();
        DispatchInstruction unknownInstruction = attemptStarter.start(unknownProbe, ATTEMPT_LEASE).orElseThrow();
        try (unknownInstruction) {
            jdbcTemplate.update(
                    "update deliveries set lease_expires_at = CURRENT_TIMESTAMP - interval '1 millisecond' where id = ?",
                    unknownInstruction.deliveryId()
            );
            assertThat(recovery.recoverExpiredStartedAttempts(1)).isEqualTo(1);
        }
        assertThat(circuit(endpoint.id()))
                .satisfies(circuit -> {
                    assertThat(circuit.state()).isEqualTo(EndpointCircuitState.OPEN);
                    assertThat(circuit.openUntil()).isNotNull();
                    assertThat(circuit.probeDeliveryId()).isNull();
                    assertThat(circuit.probeClaimToken()).isNull();
                });
    }

    private List<ClaimedDelivery> concurrentClaims() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<ClaimedDelivery>> first = executor.submit(() -> concurrentClaim(ready, start));
            Future<List<ClaimedDelivery>> second = executor.submit(() -> concurrentClaim(ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<ClaimedDelivery> claims = new java.util.ArrayList<>();
            claims.addAll(first.get(5, TimeUnit.SECONDS));
            claims.addAll(second.get(5, TimeUnit.SECONDS));
            return claims;
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<ClaimedDelivery> concurrentClaim(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent probe claims did not start");
        }
        return deliveryClaimer.claim(1, CLAIM_LEASE);
    }

    private DispatchInstruction start(UUID projectId, String idempotencyKey, String eventType) {
        eventPublisher.publish(projectId, idempotencyKey, eventType, "{\"id\":\"" + idempotencyKey + "\"}");
        return attemptStarter.start(deliveryClaimer.claim(1, CLAIM_LEASE).getFirst(), ATTEMPT_LEASE).orElseThrow();
    }

    private void openExpiredCircuit(UUID endpointId) {
        jdbcTemplate.update(
                "insert into endpoint_circuit_breakers (endpoint_id, state, consecutive_qualifying_failures, open_until) "
                        + "values (?, 'OPEN', 3, CURRENT_TIMESTAMP - interval '1 millisecond') "
                        + "on conflict (endpoint_id) do update set state = 'OPEN', consecutive_qualifying_failures = 3, "
                        + "open_until = CURRENT_TIMESTAMP - interval '1 millisecond', probe_delivery_id = null, "
                        + "probe_claim_token = null, updated_at = CURRENT_TIMESTAMP",
                endpointId
        );
    }

    private com.gialong.relayforge.delivery.application.EndpointCircuit circuit(UUID endpointId) {
        return new TransactionTemplate(transactionManager).execute(status -> circuitStore.findByEndpointId(endpointId))
                .orElseThrow();
    }

    private WebhookEndpointDetails endpoint(UUID ownerId, UUID projectId, String name, String eventType) {
        return endpointCatalog.create(
                ownerId,
                projectId,
                name,
                "https://" + name.toLowerCase().replace(' ', '-') + ".example/webhooks",
                List.of(eventType),
                true
        ).orElseThrow().endpoint();
    }

    private static DispatchObservation retryable503() {
        return DispatchObservation.httpResponse(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                503,
                Duration.ofMillis(1),
                new byte[0],
                false
        );
    }

    private static DispatchObservation succeeded204() {
        return DispatchObservation.httpResponse(
                DispatchObservation.Outcome.SUCCEEDED,
                204,
                Duration.ofMillis(1),
                new byte[0],
                false
        );
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "delivery-circuit-breaker-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
