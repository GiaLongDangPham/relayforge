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
import com.gialong.relayforge.endpoint.api.WebhookEndpointCatalog;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = {"relayforge.runtime=worker", "relayforge.worker.lifecycle-enabled=false"},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class DeliveryAttemptFinalizationIntegrationTests {

    private static final Duration INITIAL_CLAIM_LEASE = Duration.ofSeconds(15);
    private static final Duration ATTEMPT_EXECUTION_LEASE = Duration.ofSeconds(20);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_attempt_finalization_test")
            .withUsername("relayforge_attempt_finalization_test")
            .withPassword("relayforge_attempt_finalization_test");

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
    private JdbcTemplate jdbcTemplate;

    @Test
    void finalizesObservedSuccessAndClearsTheCurrentClaimAtomically() {
        ProjectDetails project = projectWithEndpoint("finalization.success.owner");
        try (DispatchInstruction instruction = start(project.id(), "finalization-success");
             DispatchObservation observation = DispatchObservation.httpResponse(
                     DispatchObservation.Outcome.SUCCEEDED,
                     204,
                     Duration.ofMillis(12),
                     "accepted".getBytes(StandardCharsets.UTF_8),
                     false
             )) {

            assertThat(finalizer.finalizeAttempt(instruction, observation)).isEqualTo(AttemptFinalizationResult.FINALIZED);

            Map<String, Object> delivery = jdbcTemplate.queryForMap(
                    "select state, attempt_count, claim_token, lease_expires_at, terminal_at from deliveries where id = ?",
                    instruction.deliveryId()
            );
            Map<String, Object> attempt = jdbcTemplate.queryForMap(
                    "select status, finished_at, http_status, latency_ms, response_preview, response_truncated "
                            + "from delivery_attempts where id = ?",
                    instruction.attemptId()
            );
            assertThat(delivery).containsEntry("state", "SUCCEEDED")
                    .containsEntry("claim_token", null)
                    .containsEntry("lease_expires_at", null);
            assertThat(((Number) delivery.get("attempt_count")).intValue()).isEqualTo(1);
            assertThat(delivery.get("terminal_at")).isNotNull();
            assertThat(attempt).containsEntry("status", "SUCCEEDED")
                    .containsEntry("response_truncated", false);
            assertThat(((Number) attempt.get("http_status")).intValue()).isEqualTo(204);
            assertThat(((Number) attempt.get("latency_ms")).intValue()).isEqualTo(12);
            assertThat(attempt.get("finished_at")).isNotNull();
            assertThat((byte[]) attempt.get("response_preview")).isEqualTo("accepted".getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void recoveryMakesTheAttemptUnknownAndALateResultCannotRewriteTheDelivery() {
        ProjectDetails project = projectWithEndpoint("finalization.recovery.owner");
        try (DispatchInstruction instruction = start(project.id(), "finalization-recovery")) {
            jdbcTemplate.update(
                    "update deliveries set lease_expires_at = CURRENT_TIMESTAMP - interval '1 millisecond' where id = ?",
                    instruction.deliveryId()
            );

            assertThat(recovery.recoverExpiredStartedAttempts(1)).isEqualTo(1);
            try (DispatchObservation lateSuccess = DispatchObservation.httpResponse(
                    DispatchObservation.Outcome.SUCCEEDED,
                    200,
                    Duration.ofMillis(30),
                    new byte[0],
                    false
            )) {
                assertThat(finalizer.finalizeAttempt(instruction, lateSuccess))
                        .isEqualTo(AttemptFinalizationResult.LATE_DIAGNOSTIC_RECORDED);
            }

            Map<String, Object> delivery = jdbcTemplate.queryForMap(
                    "select state, attempt_count, claim_token, lease_expires_at, due_at from deliveries where id = ?",
                    instruction.deliveryId()
            );
            assertThat(delivery).containsEntry("state", "PENDING")
                    .containsEntry("claim_token", null)
                    .containsEntry("lease_expires_at", null);
            assertThat(((Number) delivery.get("attempt_count")).intValue()).isEqualTo(1);
            assertThat(delivery.get("due_at")).isNotNull();
            assertThat(jdbcTemplate.queryForObject(
                    "select status from delivery_attempts where id = ?",
                    String.class,
                    instruction.attemptId()
            )).isEqualTo("UNKNOWN");
            assertThat(jdbcTemplate.queryForObject(
                    "select retry_schedule_source from delivery_attempts where id = ?",
                    String.class,
                    instruction.attemptId()
            )).isEqualTo("BACKOFF");
            assertThat(jdbcTemplate.queryForObject(
                    "select observed_status from attempt_late_diagnostics where attempt_id = ?",
                    String.class,
                    instruction.attemptId()
            )).isEqualTo("SUCCEEDED");
        }
    }

    @Test
    void staleClaimTokenCannotFinalizeTheStillStartedAttempt() {
        ProjectDetails project = projectWithEndpoint("finalization.stale.owner");
        try (DispatchInstruction instruction = start(project.id(), "finalization-stale");
             DispatchObservation observation = DispatchObservation.httpResponse(
                     DispatchObservation.Outcome.SUCCEEDED,
                     200,
                     Duration.ofMillis(10),
                     new byte[0],
                     false
             )) {
            UUID replacementToken = UUID.randomUUID();
            jdbcTemplate.update(
                    "update deliveries set claim_token = ? where id = ?",
                    replacementToken,
                    instruction.deliveryId()
            );

            assertThat(finalizer.finalizeAttempt(instruction, observation)).isEqualTo(AttemptFinalizationResult.STALE);
            assertThat(jdbcTemplate.queryForObject(
                    "select state from deliveries where id = ?",
                    String.class,
                    instruction.deliveryId()
            )).isEqualTo("CLAIMED");
            assertThat(jdbcTemplate.queryForObject(
                    "select status from delivery_attempts where id = ?",
                    String.class,
                    instruction.attemptId()
            )).isEqualTo("STARTED");
        }
    }

    @Test
    void persistsReceiverSelectedRetryAndCalculatesDueTimeWithPostgresTime() {
        ProjectDetails project = projectWithEndpoint("finalization.retry-after.owner");
        try (DispatchInstruction instruction = start(project.id(), "finalization-retry-after");
             DispatchObservation observation = DispatchObservation.httpResponse(
                     DispatchObservation.Outcome.RETRYABLE_FAILURE,
                     429,
                     Duration.ofMillis(12),
                     new byte[0],
                     false,
                     Optional.of(Duration.ofSeconds(45))
             )) {

            assertThat(finalizer.finalizeAttempt(instruction, observation)).isEqualTo(AttemptFinalizationResult.FINALIZED);

            Map<String, Object> attempt = jdbcTemplate.queryForMap(
                    "select retry_delay_ms, retry_schedule_source from delivery_attempts where id = ?",
                    instruction.attemptId()
            );
            Long persistedGapMilliseconds = jdbcTemplate.queryForObject(
                    "select round(extract(epoch from (delivery.due_at - attempt.finished_at)) * 1000)::bigint "
                            + "from deliveries delivery join delivery_attempts attempt on attempt.delivery_id = delivery.id "
                            + "where delivery.id = ? and attempt.id = ?",
                    Long.class,
                    instruction.deliveryId(),
                    instruction.attemptId()
            );
            assertThat(attempt).containsEntry("retry_delay_ms", 45000)
                    .containsEntry("retry_schedule_source", "RETRY_AFTER");
            assertThat(persistedGapMilliseconds).isEqualTo(45000L);
        }
    }

    @Test
    void appliesTheCurrentEndpointRetryFloorInsideFinalizationAndAuditsItsStrictWin() {
        UUID ownerId = bootstrap("finalization.endpoint-floor.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Endpoint floor finalization");
        var endpoint = endpointCatalog.create(
                ownerId,
                project.id(),
                "Floor receiver",
                "https://floor.example/webhooks",
                List.of("invoice.paid"),
                true,
                120
        ).orElseThrow().endpoint();

        try (DispatchInstruction instruction = start(project.id(), "finalization-endpoint-floor");
             DispatchObservation observation = DispatchObservation.httpResponse(
                     DispatchObservation.Outcome.RETRYABLE_FAILURE,
                     503,
                     Duration.ofMillis(12),
                     new byte[0],
                     false,
                     Optional.of(Duration.ofSeconds(90))
             )) {
            assertThat(finalizer.finalizeAttempt(instruction, observation)).isEqualTo(AttemptFinalizationResult.FINALIZED);

            assertThat(jdbcTemplate.queryForMap(
                    "select retry_delay_ms, retry_schedule_source from delivery_attempts where id = ?",
                    instruction.attemptId()
            )).containsEntry("retry_delay_ms", 120000)
                    .containsEntry("retry_schedule_source", "ENDPOINT_POLICY");

            Instant dueAt = jdbcTemplate.queryForObject(
                    "select due_at from deliveries where id = ?", Instant.class, instruction.deliveryId()
            );
            endpointCatalog.replaceConfiguration(
                    ownerId,
                    project.id(),
                    endpoint.id(),
                    endpoint.name(),
                    endpoint.destinationUrl(),
                    endpoint.eventTypes(),
                    null,
                    endpoint.version()
            ).orElseThrow();
            assertThat(jdbcTemplate.queryForObject(
                    "select due_at from deliveries where id = ?", Instant.class, instruction.deliveryId()
            )).isEqualTo(dueAt);
        }
    }

    @Test
    void appliesTheCurrentEndpointRetryFloorToExpiredAttemptRecovery() {
        UUID ownerId = bootstrap("recovery.endpoint-floor.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Endpoint floor recovery");
        endpointCatalog.create(
                ownerId,
                project.id(),
                "Recovery floor receiver",
                "https://recovery-floor.example/webhooks",
                List.of("invoice.paid"),
                true,
                120
        ).orElseThrow();

        try (DispatchInstruction instruction = start(project.id(), "recovery-endpoint-floor")) {
            jdbcTemplate.update(
                    "update deliveries set lease_expires_at = CURRENT_TIMESTAMP - interval '1 millisecond' where id = ?",
                    instruction.deliveryId()
            );
            assertThat(recovery.recoverExpiredStartedAttempts(1)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForMap(
                    "select retry_delay_ms, retry_schedule_source from delivery_attempts where id = ?",
                    instruction.attemptId()
            )).containsEntry("retry_delay_ms", 120000)
                    .containsEntry("retry_schedule_source", "ENDPOINT_POLICY");
        }
    }

    private DispatchInstruction start(UUID projectId, String idempotencyKey) {
        eventPublisher.publish(projectId, idempotencyKey, "invoice.paid", "{\"invoiceId\":\"" + idempotencyKey + "\"}");
        ClaimedDelivery claim = deliveryClaimer.claim(1, INITIAL_CLAIM_LEASE).getFirst();
        return attemptStarter.start(claim, ATTEMPT_EXECUTION_LEASE).orElseThrow();
    }

    private ProjectDetails projectWithEndpoint(String loginName) {
        UUID ownerId = bootstrap(loginName).ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Attempt finalization");
        endpointCatalog.create(
                ownerId,
                project.id(),
                "Finalization receiver",
                "https://finalization.example/webhooks",
                List.of("invoice.paid"),
                true
        ).orElseThrow();
        return project;
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "delivery-attempt-finalization-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
