package com.gialong.relayforge.delivery;
import com.gialong.relayforge.delivery.api.history.DeliveryDisplayStatus;
import com.gialong.relayforge.delivery.api.history.DeliveryHistory;
import com.gialong.relayforge.delivery.api.history.EventHistoryPage;
import com.gialong.relayforge.delivery.api.processing.DeliveryClaimer;
import com.gialong.relayforge.delivery.api.publish.EventPublisher;
import com.gialong.relayforge.delivery.api.publish.PublishEventResult;
import com.gialong.relayforge.delivery.api.replay.DeliveryReplayer;
import com.gialong.relayforge.delivery.api.replay.ReplayDeliveryResult;
import com.gialong.relayforge.delivery.api.replay.ReplayIdempotencyConflictException;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.history.DeliveryDisplayStatus;
import com.gialong.relayforge.delivery.api.history.DeliveryHistory;
import com.gialong.relayforge.delivery.api.history.DeliveryProjectHealth;
import com.gialong.relayforge.delivery.api.replay.DeliveryReplayer;
import com.gialong.relayforge.delivery.api.history.EventHistoryPage;
import com.gialong.relayforge.delivery.api.replay.ReplayDeliveryResult;
import com.gialong.relayforge.delivery.api.replay.ReplayIdempotencyConflictException;
import com.gialong.relayforge.delivery.api.publish.EventPublisher;
import com.gialong.relayforge.delivery.api.publish.PublishEventResult;
import com.gialong.relayforge.delivery.api.processing.DeliveryClaimer;
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
import java.util.Arrays;
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
        properties = {"relayforge.runtime=worker", "relayforge.worker.lifecycle-enabled=false"},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class DeliveryHistoryReplayIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_history_replay_test")
            .withUsername("relayforge_history_replay_test")
            .withPassword("relayforge_history_replay_test");

    @Autowired
    private OwnerBootstrap ownerBootstrap;

    @Autowired
    private ProjectCatalog projectCatalog;

    @Autowired
    private WebhookEndpointCatalog endpointCatalog;

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private DeliveryHistory deliveryHistory;

    @Autowired
    private DeliveryReplayer deliveryReplayer;

    @Autowired
    private DeliveryClaimer deliveryClaimer;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void readsOwnerScopedHistoryWithFilterBoundCursorAndSafeAttemptDetail() {
        UUID ownerId = bootstrap("history.owner").ownerId();
        UUID otherOwnerId = bootstrap("history.other.owner").ownerId();
        ProjectDetails project = projectWithEndpoint(ownerId, "History project");
        PublishEventResult payment = eventPublisher.publish(
                project.id(), "history-payment", "invoice.paid", "{\"invoiceId\":\"inv-history\"}"
        );
        eventPublisher.publish(project.id(), "history-customer", "customer.created", "{\"customerId\":\"cus-history\"}");
        exhaustWithOneRecordedAttempt(payment.eventId(), project.id(), "<receiver>&accepted");

        EventHistoryPage firstPage = deliveryHistory.listEvents(ownerId, project.id(), null, 1, null).orElseThrow();
        assertThat(firstPage.items()).hasSize(1);
        assertThat(firstPage.nextCursor()).isNotBlank();
        assertThat(deliveryHistory.listEvents(ownerId, project.id(), null, 1, firstPage.nextCursor()).orElseThrow().items())
                .hasSize(1);
        assertThatThrownBy(() -> deliveryHistory.listEvents(
                ownerId, project.id(), "invoice.paid", 1, firstPage.nextCursor()
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(deliveryHistory.findEvent(ownerId, project.id(), payment.eventId()).orElseThrow())
                .satisfies(event -> {
                    assertThat(event.payloadJson()).contains("inv-history");
                    assertThat(event.deliverySummary().totalCount()).isEqualTo(1);
                    assertThat(event.toString()).doesNotContain("inv-history");
                });
        assertThat(deliveryHistory.findEvent(otherOwnerId, project.id(), payment.eventId())).isEmpty();

        UUID deliveryId = deliveryIdForEvent(payment.eventId());
        assertThat(deliveryHistory.listDeliveries(
                ownerId, project.id(), null, null, DeliveryDisplayStatus.EXHAUSTED, 20, null
        ).orElseThrow().items()).singleElement().satisfies(delivery -> {
            assertThat(delivery.id()).isEqualTo(deliveryId);
            assertThat(delivery.displayStatus()).isEqualTo(DeliveryDisplayStatus.EXHAUSTED);
        });
        assertThat(deliveryHistory.listAttempts(ownerId, project.id(), deliveryId).orElseThrow())
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.toString()).doesNotContain("receiver"));
        UUID attemptId = jdbcTemplate.queryForObject(
                "select id from delivery_attempts where delivery_id = ?", UUID.class, deliveryId
        );
        assertThat(deliveryHistory.findAttempt(ownerId, project.id(), deliveryId, attemptId).orElseThrow())
                .satisfies(attempt -> {
                    assertThat(attempt.responsePreview()).isEqualTo("&lt;receiver&gt;&amp;accepted");
                    assertThat(attempt.destinationFingerprint()).doesNotContain("example");
                });
    }

    @Test
    void readsProjectHealthFromCurrentEndpointStateWithoutExposingDeliveryDetails() {
        UUID ownerId = bootstrap("history.health.owner").ownerId();
        UUID otherOwnerId = bootstrap("history.health.other").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "History health project");
        var createdEndpoint = endpointCatalog.create(
                ownerId,
                project.id(),
                "Health receiver",
                "https://health.example/webhooks",
                List.of("invoice.paid"),
                true
        ).orElseThrow();

        PublishEventResult due = eventPublisher.publish(project.id(), "health-due", "invoice.paid", "{}");
        PublishEventResult retry = eventPublisher.publish(project.id(), "health-retry", "invoice.paid", "{}");
        PublishEventResult inFlight = eventPublisher.publish(project.id(), "health-in-flight", "invoice.paid", "{}");
        PublishEventResult exhausted = eventPublisher.publish(project.id(), "health-exhausted", "invoice.paid", "{}");
        UUID retryDeliveryId = deliveryIdForEvent(retry.eventId());
        UUID inFlightDeliveryId = deliveryIdForEvent(inFlight.eventId());
        UUID exhaustedDeliveryId = deliveryIdForEvent(exhausted.eventId());
        jdbcTemplate.update(
                "update deliveries set attempt_count = 1, due_at = CURRENT_TIMESTAMP + interval '1 minute' where id = ?",
                retryDeliveryId
        );
        jdbcTemplate.update(
                "update deliveries set state = 'CLAIMED', due_at = null, claim_token = ?, "
                        + "lease_expires_at = CURRENT_TIMESTAMP + interval '1 minute' where id = ?",
                UUID.randomUUID(),
                inFlightDeliveryId
        );
        exhaust(exhaustedDeliveryId);

        DeliveryProjectHealth enabledHealth = deliveryHistory.findProjectHealth(ownerId, project.id()).orElseThrow();
        assertThat(enabledHealth.observedAt()).isNotNull();
        assertThat(enabledHealth.dueEnabledCount()).isEqualTo(1);
        assertThat(enabledHealth.oldestDueEnabledAt()).isNotNull();
        assertThat(enabledHealth.retryScheduledCount()).isEqualTo(1);
        assertThat(enabledHealth.inFlightCount()).isEqualTo(1);
        assertThat(enabledHealth.pausedCount()).isZero();
        assertThat(enabledHealth.exhaustedCount()).isEqualTo(1);
        assertThat(enabledHealth.toString()).doesNotContain(due.eventId().toString()).doesNotContain("health.example");
        assertThat(deliveryHistory.findProjectHealth(otherOwnerId, project.id())).isEmpty();

        endpointCatalog.setEnabled(
                ownerId,
                project.id(),
                createdEndpoint.endpoint().id(),
                false,
                createdEndpoint.endpoint().version()
        ).orElseThrow();

        DeliveryProjectHealth pausedHealth = deliveryHistory.findProjectHealth(ownerId, project.id()).orElseThrow();
        assertThat(pausedHealth.dueEnabledCount()).isZero();
        assertThat(pausedHealth.oldestDueEnabledAt()).isNull();
        assertThat(pausedHealth.retryScheduledCount()).isZero();
        assertThat(pausedHealth.inFlightCount()).isZero();
        assertThat(pausedHealth.pausedCount()).isEqualTo(3);
        assertThat(pausedHealth.exhaustedCount()).isEqualTo(1);
    }

    @Test
    void replaysOnlyExhaustedSourceIdempotentlyAndQueuesNormalPendingWork() throws Exception {
        UUID ownerId = bootstrap("replay.owner").ownerId();
        ProjectDetails project = projectWithEndpoint(ownerId, "Replay project");
        PublishEventResult sourceEvent = eventPublisher.publish(
                project.id(), "replay-source", "invoice.paid", "{\"invoiceId\":\"inv-replay\"}"
        );
        UUID sourceDeliveryId = deliveryIdForEvent(sourceEvent.eventId());
        exhaust(sourceDeliveryId);

        ReplayDeliveryResult created = deliveryReplayer.replay(ownerId, project.id(), sourceDeliveryId, "replay-one")
                .orElseThrow();
        assertThat(created.idempotentReplay()).isFalse();
        assertThat(created.sourceDeliveryId()).isEqualTo(sourceDeliveryId);
        assertThat(deliveryReplayer.replay(ownerId, project.id(), sourceDeliveryId, "replay-one").orElseThrow())
                .satisfies(replay -> {
                    assertThat(replay.replayDeliveryId()).isEqualTo(created.replayDeliveryId());
                    assertThat(replay.idempotentReplay()).isTrue();
                });
        assertThat(jdbcTemplate.queryForMap(
                "select project_id, event_id, endpoint_id, replay_of_delivery_id, state, attempt_count "
                        + "from deliveries where id = ?",
                created.replayDeliveryId()
        )).containsEntry("project_id", project.id())
                .containsEntry("event_id", sourceEvent.eventId())
                .containsEntry("replay_of_delivery_id", sourceDeliveryId)
                .containsEntry("state", "PENDING");
        assertThat(((Number) jdbcTemplate.queryForMap(
                "select attempt_count from deliveries where id = ?", created.replayDeliveryId()
        ).get("attempt_count")).intValue()).isZero();
        assertThat(jdbcTemplate.queryForObject("select state from deliveries where id = ?", String.class, sourceDeliveryId))
                .isEqualTo("EXHAUSTED");
        assertThat(deliveryClaimer.claim(10, Duration.ofSeconds(15)))
                .extracting(claim -> claim.deliveryId())
                .contains(created.replayDeliveryId());

        PublishEventResult otherEvent = eventPublisher.publish(
                project.id(), "replay-other", "invoice.paid", "{\"invoiceId\":\"inv-other\"}"
        );
        UUID otherSource = deliveryIdForEvent(otherEvent.eventId());
        exhaust(otherSource);
        assertThatThrownBy(() -> deliveryReplayer.replay(ownerId, project.id(), otherSource, "replay-one"))
                .isInstanceOf(ReplayIdempotencyConflictException.class);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ReplayDeliveryResult> first = executor.submit(() -> concurrentReplay(
                    ready, start, ownerId, project.id(), otherSource, "replay-concurrent"
            ));
            Future<ReplayDeliveryResult> second = executor.submit(() -> concurrentReplay(
                    ready, start, ownerId, project.id(), otherSource, "replay-concurrent"
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            ReplayDeliveryResult firstResult = first.get(5, TimeUnit.SECONDS);
            ReplayDeliveryResult secondResult = second.get(5, TimeUnit.SECONDS);
            assertThat(firstResult.replayDeliveryId()).isEqualTo(secondResult.replayDeliveryId());
            assertThat(List.of(firstResult.idempotentReplay(), secondResult.idempotentReplay()))
                    .containsExactlyInAnyOrder(false, true);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from replay_requests where project_id = ? and idempotency_key = ?",
                    Integer.class,
                    project.id(),
                    "replay-concurrent"
            )).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ReplayDeliveryResult concurrentReplay(
            CountDownLatch ready,
            CountDownLatch start,
            UUID ownerId,
            UUID projectId,
            UUID sourceDeliveryId,
            String idempotencyKey
    ) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent replay did not start");
        }
        return deliveryReplayer.replay(ownerId, projectId, sourceDeliveryId, idempotencyKey).orElseThrow();
    }

    private ProjectDetails projectWithEndpoint(UUID ownerId, String name) {
        ProjectDetails project = projectCatalog.create(ownerId, name);
        endpointCatalog.create(
                ownerId,
                project.id(),
                "History receiver",
                "https://history.example/webhooks",
                List.of("invoice.paid"),
                true
        ).orElseThrow();
        return project;
    }

    private void exhaustWithOneRecordedAttempt(UUID eventId, UUID projectId, String responsePreview) {
        UUID deliveryId = deliveryIdForEvent(eventId);
        exhaust(deliveryId);
        jdbcTemplate.update(
                "insert into delivery_attempts (id, delivery_id, attempt_number, claim_token, status, "
                        + "destination_fingerprint_version, destination_fingerprint, finished_at, response_preview, "
                        + "response_truncated) values (?, ?, 1, ?, 'RETRYABLE_FAILURE', 1, ?, CURRENT_TIMESTAMP, ?, false)",
                UUID.randomUUID(),
                deliveryId,
                UUID.randomUUID(),
                new byte[32],
                responsePreview.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void exhaust(UUID deliveryId) {
        jdbcTemplate.update(
                "update deliveries set state = 'EXHAUSTED', due_at = null, attempt_count = 5, "
                        + "terminal_at = CURRENT_TIMESTAMP where id = ?",
                deliveryId
        );
    }

    private UUID deliveryIdForEvent(UUID eventId) {
        return jdbcTemplate.queryForObject("select id from deliveries where event_id = ?", UUID.class, eventId);
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "delivery-history-replay-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
