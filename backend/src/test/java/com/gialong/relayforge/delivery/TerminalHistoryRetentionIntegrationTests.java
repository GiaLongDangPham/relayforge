package com.gialong.relayforge.delivery;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.EventPublisher;
import com.gialong.relayforge.delivery.api.PublishEventResult;
import com.gialong.relayforge.delivery.api.RetentionCleanupResult;
import com.gialong.relayforge.delivery.api.TerminalHistoryRetention;
import com.gialong.relayforge.delivery.api.DeliveryReplayer;
import com.gialong.relayforge.delivery.api.ReplayDeliveryResult;
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

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = {"relayforge.runtime=worker", "relayforge.worker.lifecycle-enabled=false", "relayforge.retention.enabled=false"},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class TerminalHistoryRetentionIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_terminal_retention_test")
            .withUsername("relayforge_terminal_retention_test")
            .withPassword("relayforge_terminal_retention_test");

    @Autowired
    private OwnerBootstrap ownerBootstrap;

    @Autowired
    private ProjectCatalog projectCatalog;

    @Autowired
    private WebhookEndpointCatalog endpointCatalog;

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private DeliveryReplayer deliveryReplayer;

    @Autowired
    private TerminalHistoryRetention retention;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void deletesAnExpiredCompleteTerminalReplayGraphAndZeroRouteEventWithoutTouchingConfiguration() {
        UUID ownerId = bootstrap("retention.complete.owner").ownerId();
        ProjectDetails project = projectWithEndpoint(ownerId, "Retention complete graph");
        PublishEventResult terminalEvent = eventPublisher.publish(
                project.id(), "retention-complete", "invoice.paid", "{\"invoiceId\":\"retention-complete\"}"
        );
        UUID sourceDeliveryId = deliveryIdForEvent(terminalEvent.eventId());
        makeExhaustedOld(sourceDeliveryId);
        UUID sourceAttemptId = insertUnknownAttemptOld(sourceDeliveryId);
        insertLateDiagnostic(sourceAttemptId);

        UUID replayDeliveryId = deliveryReplayer.replay(
                ownerId, project.id(), sourceDeliveryId, "retention-replay"
        ).orElseThrow().replayDeliveryId();
        makeSucceededOld(replayDeliveryId);
        insertSucceededAttemptOld(replayDeliveryId);
        makeEventOld(terminalEvent.eventId());

        PublishEventResult zeroRouteEvent = eventPublisher.publish(
                project.id(), "retention-zero-route", "customer.created", "{\"customerId\":\"retention-zero\"}"
        );
        makeEventOld(zeroRouteEvent.eventId());

        RetentionCleanupResult firstRun = retention.cleanExpiredTerminalHistory(30, 10);

        assertThat(firstRun).isEqualTo(new RetentionCleanupResult(2, 2, 2, 1, 1));
        assertThat(count("events", terminalEvent.eventId())).isZero();
        assertThat(count("events", zeroRouteEvent.eventId())).isZero();
        assertThat(count("deliveries", sourceDeliveryId)).isZero();
        assertThat(count("deliveries", replayDeliveryId)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from delivery_attempts", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from attempt_late_diagnostics", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("select count(*) from replay_requests", Integer.class)).isZero();
        assertThat(count("projects", project.id())).isOne();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from webhook_endpoints where project_id = ?", Integer.class, project.id()
        )).isOne();

        assertThat(retention.cleanExpiredTerminalHistory(30, 10)).isEqualTo(RetentionCleanupResult.empty());
    }

    @Test
    void retainsNonterminalWorkAndAnyGraphWithANewerReplayChild() {
        UUID ownerId = bootstrap("retention.protected.owner").ownerId();
        ProjectDetails project = projectWithEndpoint(ownerId, "Retention protected graph");
        PublishEventResult pendingEvent = eventPublisher.publish(
                project.id(), "retention-pending", "invoice.paid", "{\"invoiceId\":\"retention-pending\"}"
        );
        makeEventOld(pendingEvent.eventId());

        PublishEventResult sourceEvent = eventPublisher.publish(
                project.id(), "retention-replay-pending", "invoice.paid", "{\"invoiceId\":\"retention-replay\"}"
        );
        UUID sourceDeliveryId = deliveryIdForEvent(sourceEvent.eventId());
        makeExhaustedOld(sourceDeliveryId);
        makeEventOld(sourceEvent.eventId());
        UUID replayDeliveryId = deliveryReplayer.replay(
                ownerId, project.id(), sourceDeliveryId, "retention-pending-replay"
        ).orElseThrow().replayDeliveryId();

        assertThat(retention.cleanExpiredTerminalHistory(30, 10)).isEqualTo(RetentionCleanupResult.empty());
        assertThat(count("events", pendingEvent.eventId())).isOne();
        assertThat(count("events", sourceEvent.eventId())).isOne();
        assertThat(count("deliveries", sourceDeliveryId)).isOne();
        assertThat(count("deliveries", replayDeliveryId)).isOne();
        assertThat(jdbcTemplate.queryForObject("select state from deliveries where id = ?", String.class, replayDeliveryId))
                .isEqualTo("PENDING");
    }

    @Test
    void concurrentReplayAndRetentionLeaveEitherTheWholeGraphOrNoGraph() throws Exception {
        UUID ownerId = bootstrap("retention.concurrent.owner").ownerId();
        ProjectDetails project = projectWithEndpoint(ownerId, "Retention concurrent graph");
        PublishEventResult sourceEvent = eventPublisher.publish(
                project.id(), "retention-concurrent", "invoice.paid", "{\"invoiceId\":\"retention-concurrent\"}"
        );
        UUID sourceDeliveryId = deliveryIdForEvent(sourceEvent.eventId());
        makeExhaustedOld(sourceDeliveryId);
        makeEventOld(sourceEvent.eventId());

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<Optional<ReplayDeliveryResult>> replay = executor.submit(() -> {
                start.await();
                return deliveryReplayer.replay(ownerId, project.id(), sourceDeliveryId, "retention-concurrent-replay");
            });
            Future<RetentionCleanupResult> cleanup = executor.submit(() -> {
                start.await();
                return retention.cleanExpiredTerminalHistory(30, 1);
            });
            start.countDown();

            Optional<ReplayDeliveryResult> replayResult = replay.get();
            RetentionCleanupResult cleanupResult = cleanup.get();

            if (replayResult.isPresent()) {
                assertThat(cleanupResult).isEqualTo(RetentionCleanupResult.empty());
                assertThat(count("events", sourceEvent.eventId())).isOne();
                assertThat(count("deliveries", sourceDeliveryId)).isOne();
                assertThat(count("deliveries", replayResult.orElseThrow().replayDeliveryId())).isOne();
            } else {
                assertThat(cleanupResult.eventsDeleted()).isOne();
                assertThat(count("events", sourceEvent.eventId())).isZero();
                assertThat(count("deliveries", sourceDeliveryId)).isZero();
            }
        }
    }

    private ProjectDetails projectWithEndpoint(UUID ownerId, String name) {
        ProjectDetails project = projectCatalog.create(ownerId, name);
        endpointCatalog.create(
                ownerId,
                project.id(),
                "Retention receiver",
                "https://retention.example/webhooks",
                List.of("invoice.paid"),
                true
        ).orElseThrow();
        return project;
    }

    private void makeEventOld(UUID eventId) {
        jdbcTemplate.update(
                "update events set accepted_at = CURRENT_TIMESTAMP - interval '31 days' where id = ?",
                eventId
        );
    }

    private void makeExhaustedOld(UUID deliveryId) {
        jdbcTemplate.update(
                "update deliveries set state = 'EXHAUSTED', due_at = null, claim_token = null, lease_expires_at = null, "
                        + "attempt_count = 5, terminal_at = CURRENT_TIMESTAMP - interval '31 days', "
                        + "updated_at = CURRENT_TIMESTAMP - interval '31 days' where id = ?",
                deliveryId
        );
    }

    private void makeSucceededOld(UUID deliveryId) {
        jdbcTemplate.update(
                "update deliveries set state = 'SUCCEEDED', due_at = null, claim_token = null, lease_expires_at = null, "
                        + "attempt_count = 1, terminal_at = CURRENT_TIMESTAMP - interval '31 days', "
                        + "updated_at = CURRENT_TIMESTAMP - interval '31 days' where id = ?",
                deliveryId
        );
    }

    private UUID insertUnknownAttemptOld(UUID deliveryId) {
        UUID attemptId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into delivery_attempts (id, delivery_id, attempt_number, claim_token, status, "
                        + "destination_fingerprint_version, destination_fingerprint, finished_at, response_truncated) "
                        + "values (?, ?, 1, ?, 'UNKNOWN', 1, ?, CURRENT_TIMESTAMP - interval '31 days', false)",
                attemptId,
                deliveryId,
                UUID.randomUUID(),
                new byte[32]
        );
        return attemptId;
    }

    private void insertSucceededAttemptOld(UUID deliveryId) {
        jdbcTemplate.update(
                "insert into delivery_attempts (id, delivery_id, attempt_number, claim_token, status, "
                        + "destination_fingerprint_version, destination_fingerprint, finished_at, response_truncated) "
                        + "values (?, ?, 1, ?, 'SUCCEEDED', 1, ?, CURRENT_TIMESTAMP - interval '31 days', false)",
                UUID.randomUUID(),
                deliveryId,
                UUID.randomUUID(),
                new byte[32]
        );
    }

    private void insertLateDiagnostic(UUID attemptId) {
        jdbcTemplate.update(
                "insert into attempt_late_diagnostics (id, attempt_id, claim_token, observed_status) values (?, ?, ?, 'SUCCEEDED')",
                UUID.randomUUID(),
                attemptId,
                UUID.randomUUID()
        );
    }

    private UUID deliveryIdForEvent(UUID eventId) {
        return jdbcTemplate.queryForObject("select id from deliveries where event_id = ?", UUID.class, eventId);
    }

    private int count(String table, UUID id) {
        return jdbcTemplate.queryForObject("select count(*) from " + table + " where id = ?", Integer.class, id);
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "terminal-history-retention-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
