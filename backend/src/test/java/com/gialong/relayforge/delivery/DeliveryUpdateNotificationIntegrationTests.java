package com.gialong.relayforge.delivery;

import com.gialong.relayforge.RelayForgeApplication;
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
import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = {"relayforge.runtime=worker", "relayforge.worker.lifecycle-enabled=false"},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class DeliveryUpdateNotificationIntegrationTests {

    private static final Duration INITIAL_CLAIM_LEASE = Duration.ofSeconds(15);
    private static final Duration ATTEMPT_EXECUTION_LEASE = Duration.ofSeconds(20);

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_delivery_update_notification_test")
            .withUsername("relayforge_delivery_update_notification_test")
            .withPassword("relayforge_delivery_update_notification_test");

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
    void committedFinalizationAndRecoveryNotifyButAStaleFinalizationDoesNot() throws Exception {
        ProjectDetails project = projectWithEndpoint("delivery.update.notification.owner");
        try (Connection connection = listeningConnection()) {
            PGConnection listener = connection.unwrap(PGConnection.class);

            try (DispatchInstruction finalized = start(project.id(), "delivery-update-finalized");
                 DispatchObservation observation = DispatchObservation.httpResponse(
                         DispatchObservation.Outcome.SUCCEEDED,
                         204,
                         Duration.ofMillis(10),
                         new byte[0],
                         false
                 )) {
                finalizer.finalizeAttempt(finalized, observation);
                assertThat(nextPayload(listener, Duration.ofSeconds(2)))
                        .isEqualTo(project.id() + ":" + finalized.deliveryId());
            }

            try (DispatchInstruction recovered = start(project.id(), "delivery-update-recovered")) {
                jdbcTemplate.update(
                        "update deliveries set lease_expires_at = CURRENT_TIMESTAMP - interval '1 millisecond' where id = ?",
                        recovered.deliveryId()
                );
                assertThat(recovery.recoverExpiredStartedAttempts(1)).isEqualTo(1);
                assertThat(nextPayload(listener, Duration.ofSeconds(2)))
                        .isEqualTo(project.id() + ":" + recovered.deliveryId());
            }

            try (DispatchInstruction stale = start(project.id(), "delivery-update-stale");
                 DispatchObservation observation = DispatchObservation.httpResponse(
                         DispatchObservation.Outcome.SUCCEEDED,
                         204,
                         Duration.ofMillis(10),
                         new byte[0],
                         false
                 )) {
                jdbcTemplate.update("update deliveries set claim_token = ? where id = ?", UUID.randomUUID(), stale.deliveryId());
                finalizer.finalizeAttempt(stale, observation);
                assertThat(nextPayload(listener, Duration.ofMillis(250))).isNull();
            }
        }
    }

    private Connection listeningConnection() throws Exception {
        Connection connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("listen relayforge_delivery_updates");
        }
        return connection;
    }

    private static String nextPayload(PGConnection listener, Duration timeout) throws Exception {
        PGNotification[] notifications = listener.getNotifications(Math.toIntExact(timeout.toMillis()));
        if (notifications == null || notifications.length == 0) {
            return null;
        }
        return notifications[0].getParameter();
    }

    private DispatchInstruction start(UUID projectId, String idempotencyKey) {
        eventPublisher.publish(projectId, idempotencyKey, "invoice.paid", "{\"invoiceId\":\"" + idempotencyKey + "\"}");
        ClaimedDelivery claim = deliveryClaimer.claim(1, INITIAL_CLAIM_LEASE).getFirst();
        return attemptStarter.start(claim, ATTEMPT_EXECUTION_LEASE).orElseThrow();
    }

    private ProjectDetails projectWithEndpoint(String loginName) {
        UUID ownerId = bootstrap(loginName);
        ProjectDetails project = projectCatalog.create(ownerId, "Delivery update notification");
        endpointCatalog.create(
                ownerId,
                project.id(),
                "Delivery update receiver",
                "https://delivery-update.example/webhooks",
                List.of("invoice.paid"),
                true
        ).orElseThrow();
        return project;
    }

    private UUID bootstrap(String loginName) {
        char[] password = "delivery-update-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password).ownerId();
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
