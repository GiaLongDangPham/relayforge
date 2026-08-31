package com.gialong.relayforge.delivery;
import com.gialong.relayforge.delivery.api.publish.EventPublisher;
import com.gialong.relayforge.delivery.api.publish.PublishEventResult;
import com.gialong.relayforge.delivery.api.publish.PublishIdempotencyConflictException;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.publish.EventPublisher;
import com.gialong.relayforge.delivery.api.publish.PublishEventResult;
import com.gialong.relayforge.delivery.api.publish.PublishIdempotencyConflictException;
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
        properties = "relayforge.runtime=api",
        webEnvironment = SpringBootTest.WebEnvironment.MOCK
)
class PublishEventIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_publish_test")
            .withUsername("relayforge_publish_test")
            .withPassword("relayforge_publish_test");

    @Autowired
    private OwnerBootstrap ownerBootstrap;

    @Autowired
    private ProjectCatalog projectCatalog;

    @Autowired
    private WebhookEndpointCatalog endpointCatalog;

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void atomicallyRoutesEnabledExactSubscriptionsAndMakesEquivalentRetrySafe() throws Exception {
        UUID ownerId = bootstrap("publish.first.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Payments");
        WebhookEndpointDetails firstMatch = endpoint(ownerId, project.id(), "First", List.of("invoice.paid"), true);
        WebhookEndpointDetails secondMatch = endpoint(ownerId, project.id(), "Second", List.of("invoice.paid"), true);
        endpoint(ownerId, project.id(), "Disabled", List.of("invoice.paid"), false);
        endpoint(ownerId, project.id(), "Other event", List.of("invoice.failed"), true);

        PublishEventResult accepted = eventPublisher.publish(
                project.id(),
                "payment-invoice-123",
                " invoice.paid ",
                "{\"invoiceId\":\"inv-123\",\"amount\":4200}"
        );
        assertThat(accepted.eventType()).isEqualTo("invoice.paid");
        assertThat(accepted.deliveryCount()).isEqualTo(2);
        assertThat(accepted.idempotentReplay()).isFalse();
        assertThat(deliveredEndpoints(accepted.eventId())).containsExactlyInAnyOrder(firstMatch.id(), secondMatch.id());

        PublishEventResult replay = eventPublisher.publish(
                project.id(),
                "payment-invoice-123",
                "invoice.paid",
                "{\"amount\":4200,\"invoiceId\":\"inv-123\"}"
        );
        assertThat(replay.eventId()).isEqualTo(accepted.eventId());
        assertThat(replay.acceptedAt()).isEqualTo(accepted.acceptedAt());
        assertThat(replay.deliveryCount()).isEqualTo(2);
        assertThat(replay.idempotentReplay()).isTrue();
        assertThat(deliveredEndpoints(accepted.eventId())).containsExactlyInAnyOrder(firstMatch.id(), secondMatch.id());

        endpointCatalog.replaceConfiguration(
                ownerId,
                project.id(),
                firstMatch.id(),
                firstMatch.name(),
                firstMatch.destinationUrl(),
                List.of("invoice.failed"),
                firstMatch.version()
        ).orElseThrow();
        assertThat(deliveredEndpoints(accepted.eventId())).containsExactlyInAnyOrder(firstMatch.id(), secondMatch.id());

        assertThatThrownBy(() -> eventPublisher.publish(
                project.id(),
                "payment-invoice-123",
                "invoice.paid",
                "{\"invoiceId\":\"inv-123\",\"amount\":4300}"
        )).isInstanceOf(PublishIdempotencyConflictException.class);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from events where project_id = ? and idempotency_key = ?",
                Integer.class,
                project.id(),
                "payment-invoice-123"
        )).isEqualTo(1);
        assertThat(deliveredEndpoints(accepted.eventId())).containsExactlyInAnyOrder(firstMatch.id(), secondMatch.id());

        PublishEventResult noRoute = eventPublisher.publish(
                project.id(),
                "customer-123",
                "customer.created",
                "{\"customerId\":\"customer-123\"}"
        );
        assertThat(noRoute.deliveryCount()).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from events where id = ?", Integer.class, noRoute.eventId()
        )).isEqualTo(1);
        assertThat(deliveredEndpoints(noRoute.eventId())).isEmpty();
    }

    @Test
    void convergesConcurrentEquivalentPublishesToOneEventAndOneDeliverySet() throws Exception {
        UUID ownerId = bootstrap("publish.concurrent.owner").ownerId();
        ProjectDetails project = projectCatalog.create(ownerId, "Concurrent payments");
        endpoint(ownerId, project.id(), "Receiver", List.of("invoice.paid"), true);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PublishEventResult> first = executor.submit(() -> publishFromConcurrentContender(ready, start, project.id()));
            Future<PublishEventResult> second = executor.submit(() -> publishFromConcurrentContender(ready, start, project.id()));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            PublishEventResult firstResult = first.get(5, TimeUnit.SECONDS);
            PublishEventResult secondResult = second.get(5, TimeUnit.SECONDS);
            assertThat(firstResult.eventId()).isEqualTo(secondResult.eventId());
            assertThat(List.of(firstResult.idempotentReplay(), secondResult.idempotentReplay()))
                    .containsExactlyInAnyOrder(false, true);
            assertThat(firstResult.deliveryCount()).isEqualTo(1);
            assertThat(secondResult.deliveryCount()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from events where project_id = ? and idempotency_key = ?",
                    Integer.class,
                    project.id(),
                    "concurrent-payment"
            )).isEqualTo(1);
            assertThat(deliveredEndpoints(firstResult.eventId())).hasSize(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private PublishEventResult publishFromConcurrentContender(CountDownLatch ready, CountDownLatch start, UUID projectId)
            throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent test did not start");
        }
        return eventPublisher.publish(
                projectId,
                "concurrent-payment",
                "invoice.paid",
                "{\"amount\":4200,\"invoiceId\":\"inv-concurrent\"}"
        );
    }

    private WebhookEndpointDetails endpoint(
            UUID ownerId,
            UUID projectId,
            String name,
            List<String> eventTypes,
            boolean enabled
    ) {
        return endpointCatalog.create(
                ownerId,
                projectId,
                name,
                "https://" + name.toLowerCase().replace(' ', '-') + ".example/webhooks",
                eventTypes,
                enabled
        ).orElseThrow().endpoint();
    }

    private List<UUID> deliveredEndpoints(UUID eventId) {
        return jdbcTemplate.queryForList(
                "select endpoint_id from deliveries where event_id = ? order by endpoint_id",
                UUID.class,
                eventId
        );
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "publish-event-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

}
