package com.gialong.relayforge.delivery;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.DeliveryAttemptStarter;
import com.gialong.relayforge.delivery.api.DeliveryClaimer;
import com.gialong.relayforge.delivery.api.DispatchInstruction;
import com.gialong.relayforge.delivery.api.EventPublisher;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
class DeliveryAttemptStartIntegrationTests {

    private static final Duration INITIAL_CLAIM_LEASE = Duration.ofSeconds(15);
    private static final Duration ATTEMPT_EXECUTION_LEASE = Duration.ofSeconds(20);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_attempt_start_test")
            .withUsername("relayforge_attempt_start_test")
            .withPassword("relayforge_attempt_start_test");

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
    private JdbcTemplate jdbcTemplate;

    @Test
    void atomicallyStartsAnAttemptSnapshotsDispatchDataAndExtendsTheLease() throws Exception {
        UUID ownerId = bootstrap("attempt.start.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Attempt start");
        String destinationUrl = "https://attempt-start.example/webhooks";
        endpoint(ownerId, project.id(), "Attempt receiver", destinationUrl, true);
        var accepted = eventPublisher.publish(
                project.id(),
                "attempt-start-event",
                "invoice.paid",
                "{\"invoiceId\":\"attempt-1\"}"
        );
        ClaimedDelivery claim = deliveryClaimer.claim(1, INITIAL_CLAIM_LEASE).getFirst();

        Optional<DispatchInstruction> started = deliveryAttemptStarter.start(claim, ATTEMPT_EXECUTION_LEASE);

        assertThat(started).isPresent();
        try (DispatchInstruction instruction = started.orElseThrow()) {
            assertThat(instruction.projectId()).isEqualTo(project.id());
            assertThat(instruction.eventId()).isEqualTo(accepted.eventId());
            assertThat(instruction.deliveryId()).isEqualTo(claim.deliveryId());
            assertThat(instruction.claimToken()).isEqualTo(claim.claimToken());
            assertThat(instruction.attemptNumber()).isEqualTo(1);
            assertThat(instruction.eventType()).isEqualTo("invoice.paid");
            assertThat(instruction.destinationUrl()).isEqualTo(destinationUrl);
            assertThat(new String(instruction.payloadJson(), StandardCharsets.UTF_8)).contains("attempt-1");
            byte[] signingSecret = instruction.signingSecret();
            try {
                assertThat(signingSecret).hasSize(32);
            } finally {
                Arrays.fill(signingSecret, (byte) 0);
            }
            assertThat(instruction.toString()).doesNotContain(destinationUrl, "attempt-1");
        }

        Map<String, Object> delivery = jdbcTemplate.queryForMap(
                "select state, attempt_count, claim_token, lease_expires_at from deliveries where id = ?",
                claim.deliveryId()
        );
        assertThat(delivery).containsEntry("state", "CLAIMED");
        assertThat(((Number) delivery.get("attempt_count")).intValue()).isEqualTo(1);
        assertThat(delivery.get("claim_token")).isEqualTo(claim.claimToken());

        Map<String, Object> attempt = jdbcTemplate.queryForMap(
                "select id, attempt_number, claim_token, status, destination_fingerprint_version, "
                        + "destination_fingerprint, started_at from delivery_attempts where delivery_id = ?",
                claim.deliveryId()
        );
        assertThat(attempt).containsEntry("status", "STARTED")
                .containsEntry("claim_token", claim.claimToken());
        assertThat(((Number) attempt.get("attempt_number")).intValue()).isEqualTo(1);
        assertThat(((Number) attempt.get("destination_fingerprint_version")).intValue()).isEqualTo(1);
        assertThat((byte[]) attempt.get("destination_fingerprint"))
                .isEqualTo(destinationFingerprint(destinationUrl));
        Instant startedAt = timestampOf(attempt.get("started_at"));
        Instant leaseExpiresAt = timestampOf(delivery.get("lease_expires_at"));
        assertThat(Duration.between(startedAt, leaseExpiresAt)).isEqualTo(ATTEMPT_EXECUTION_LEASE);

        jdbcTemplate.update(
                "update deliveries set lease_expires_at = CURRENT_TIMESTAMP - interval '1 millisecond' where id = ?",
                claim.deliveryId()
        );
        assertThat(deliveryClaimer.recoverExpiredPreAttemptClaims(1)).isZero();
        assertThat(jdbcTemplate.queryForObject("select state from deliveries where id = ?", String.class, claim.deliveryId()))
                .isEqualTo("CLAIMED");
    }

    @Test
    void expiredClaimsDoNotStartAndDisabledEndpointsReleaseWithoutConsumingBudget() {
        UUID ownerId = bootstrap("attempt.release.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Attempt release");
        WebhookEndpointDetails endpoint = endpoint(
                ownerId,
                project.id(),
                "Release receiver",
                "https://attempt-release.example/webhooks",
                true
        );

        ClaimedDelivery expired = publishAndClaim(project.id(), "expired-attempt-start");
        jdbcTemplate.update(
                "update deliveries set lease_expires_at = CURRENT_TIMESTAMP - interval '1 millisecond' where id = ?",
                expired.deliveryId()
        );
        assertThat(deliveryAttemptStarter.start(expired, ATTEMPT_EXECUTION_LEASE)).isEmpty();
        assertThat(attemptCount(expired.deliveryId())).isZero();

        ClaimedDelivery current = publishAndClaim(project.id(), "disabled-attempt-start");
        endpointCatalog.setEnabled(ownerId, project.id(), endpoint.id(), false, endpoint.version()).orElseThrow();
        assertThat(deliveryAttemptStarter.start(current, ATTEMPT_EXECUTION_LEASE)).isEmpty();
        Map<String, Object> released = jdbcTemplate.queryForMap(
                "select state, attempt_count, claim_token, lease_expires_at from deliveries where id = ?",
                current.deliveryId()
        );
        assertThat(released).containsEntry("state", "PENDING")
                .containsEntry("claim_token", null)
                .containsEntry("lease_expires_at", null);
        assertThat(((Number) released.get("attempt_count")).intValue()).isZero();
    }

    @Test
    void concurrentAttemptStartsForOneCurrentClaimCreateExactlyOneStartedAttempt() throws Exception {
        UUID ownerId = bootstrap("attempt.concurrent.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Concurrent attempt start");
        endpoint(ownerId, project.id(), "Concurrent receiver", "https://attempt-concurrent.example/webhooks", true);
        ClaimedDelivery claim = publishAndClaim(project.id(), "concurrent-attempt-start");

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<DispatchInstruction>> first = executor.submit(() -> startConcurrently(ready, start, claim));
            Future<Optional<DispatchInstruction>> second = executor.submit(() -> startConcurrently(ready, start, claim));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Optional<DispatchInstruction>> results = List.of(
                    first.get(5, TimeUnit.SECONDS),
                    second.get(5, TimeUnit.SECONDS)
            );
            assertThat(results).filteredOn(Optional::isPresent).hasSize(1);
            results.stream().flatMap(Optional::stream).forEach(DispatchInstruction::close);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(attemptCount(claim.deliveryId())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from delivery_attempts where delivery_id = ? and status = 'STARTED'",
                Integer.class,
                claim.deliveryId()
        )).isEqualTo(1);
    }

    private ClaimedDelivery publishAndClaim(UUID projectId, String idempotencyKey) {
        eventPublisher.publish(projectId, idempotencyKey, "invoice.paid", "{\"invoiceId\":\"" + idempotencyKey + "\"}");
        return deliveryClaimer.claim(1, INITIAL_CLAIM_LEASE).getFirst();
    }

    private WebhookEndpointDetails endpoint(
            UUID ownerId,
            UUID projectId,
            String name,
            String destinationUrl,
            boolean enabled
    ) {
        return endpointCatalog.create(
                ownerId,
                projectId,
                name,
                destinationUrl,
                List.of("invoice.paid"),
                enabled
        ).orElseThrow().endpoint();
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "delivery-attempt-start-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private Optional<DispatchInstruction> startConcurrently(
            CountDownLatch ready,
            CountDownLatch start,
            ClaimedDelivery claim
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent attempt starts did not begin");
        }
        return deliveryAttemptStarter.start(claim, ATTEMPT_EXECUTION_LEASE);
    }

    private int attemptCount(UUID deliveryId) {
        Number count = jdbcTemplate.queryForObject(
                "select attempt_count from deliveries where id = ?",
                Number.class,
                deliveryId
        );
        return count == null ? -1 : count.intValue();
    }

    private static byte[] destinationFingerprint(String destinationUrl) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("relayforge.destination.v1\u0000".getBytes(StandardCharsets.UTF_8));
        return digest.digest(destinationUrl.getBytes(StandardCharsets.UTF_8));
    }

    private static Instant timestampOf(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toInstant();
        }
        return ((java.sql.Timestamp) value).toInstant();
    }
}
