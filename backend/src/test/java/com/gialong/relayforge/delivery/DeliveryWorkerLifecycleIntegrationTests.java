package com.gialong.relayforge.delivery;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.EventPublisher;
import com.gialong.relayforge.endpoint.api.WebhookEndpointCatalog;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = {"relayforge.runtime=worker", "relayforge.endpoint.allow-local-http=true"},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class DeliveryWorkerLifecycleIntegrationTests {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_worker_lifecycle_test")
            .withUsername("relayforge_worker_lifecycle_test")
            .withPassword("relayforge_worker_lifecycle_test");

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
    void workerPollsClaimsDispatchesOnceAndFinalizesTheDelivery() throws Exception {
        AtomicInteger received = new AtomicInteger();
        HttpServer receiver = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        receiver.createContext("/webhooks", exchange -> respond(exchange, received));
        receiver.start();
        try {
            UUID ownerId = bootstrap("worker.lifecycle.owner").ownerId();
            ProjectDetails project = projectCatalog.create(ownerId, "Worker lifecycle");
            endpointCatalog.create(
                    ownerId,
                    project.id(),
                    "Local receiver",
                    "http://127.0.0.1:" + receiver.getAddress().getPort() + "/webhooks",
                    List.of("invoice.paid"),
                    true
            ).orElseThrow();
            var accepted = eventPublisher.publish(
                    project.id(),
                    "worker-lifecycle-event",
                    "invoice.paid",
                    "{\"invoiceId\":\"worker-1\"}"
            );

            assertThat(awaitDeliveryState(accepted.eventId(), Duration.ofSeconds(5))).isEqualTo("SUCCEEDED");
            assertThat(received).hasValue(1);
            assertThat(jdbcTemplate.queryForObject(
                    "select status from delivery_attempts where delivery_id = (select id from deliveries where event_id = ?)",
                    String.class,
                    accepted.eventId()
            )).isEqualTo("SUCCEEDED");
        } finally {
            receiver.stop(0);
        }
    }

    private String awaitDeliveryState(UUID eventId, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        String state = null;
        while (System.nanoTime() < deadline) {
            state = jdbcTemplate.queryForObject(
                    "select state from deliveries where event_id = ?",
                    String.class,
                    eventId
            );
            if ("SUCCEEDED".equals(state)) {
                return state;
            }
            Thread.sleep(25);
        }
        return state;
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "worker-lifecycle-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void respond(HttpExchange exchange, AtomicInteger received) throws IOException {
        received.incrementAndGet();
        exchange.sendResponseHeaders(204, -1);
        exchange.close();
    }
}
