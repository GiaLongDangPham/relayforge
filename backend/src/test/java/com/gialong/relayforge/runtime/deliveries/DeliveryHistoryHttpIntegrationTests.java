package com.gialong.relayforge.runtime.deliveries;
import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.processing.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptFinalizer;
import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptStarter;
import com.gialong.relayforge.delivery.api.processing.DeliveryClaimer;
import com.gialong.relayforge.delivery.api.processing.DispatchInstruction;
import com.gialong.relayforge.delivery.api.processing.DispatchObservation;
import com.gialong.relayforge.delivery.api.publish.EventPublisher;
import com.gialong.relayforge.delivery.api.publish.PublishEventResult;
import com.gialong.relayforge.endpoint.api.WebhookEndpointCatalog;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpResponse.BodyHandlers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class DeliveryHistoryHttpIntegrationTests {

    private static final String OWNER_LOGIN = "history.http.owner";
    private static final String OTHER_OWNER_LOGIN = "history.http.other";
    private static final String PASSWORD = "history-http-test-password";
    private static final Pattern CSRF_TOKEN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_history_http_test")
            .withUsername("relayforge_history_http_test")
            .withPassword("relayforge_history_http_test");

    @Test
    void ownerHistoryIsScopedAndReplayIsCsrfProtectedAndIdempotent() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            OwnerBootstrap ownerBootstrap = context.getBean(OwnerBootstrap.class);
            ProjectCatalog projectCatalog = context.getBean(ProjectCatalog.class);
            WebhookEndpointCatalog endpointCatalog = context.getBean(WebhookEndpointCatalog.class);
            EventPublisher eventPublisher = context.getBean(EventPublisher.class);
            DeliveryClaimer deliveryClaimer = context.getBean(DeliveryClaimer.class);
            DeliveryAttemptStarter attemptStarter = context.getBean(DeliveryAttemptStarter.class);
            DeliveryAttemptFinalizer finalizer = context.getBean(DeliveryAttemptFinalizer.class);
            MeterRegistry meterRegistry = context.getBean(MeterRegistry.class);
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            UUID ownerId = bootstrap(ownerBootstrap, OWNER_LOGIN).ownerId();
            UUID otherOwnerId = bootstrap(ownerBootstrap, OTHER_OWNER_LOGIN).ownerId();
            ProjectDetails project = projectCatalog.create(ownerId, "History HTTP project");
            endpointCatalog.create(
                    ownerId,
                    project.id(),
                    "History HTTP receiver",
                    "http://localhost:8080/receiver",
                    List.of("invoice.paid"),
                    true
            ).orElseThrow();
            PublishEventResult accepted = eventPublisher.publish(
                    project.id(), "history-http-event", "invoice.paid", "{\"invoiceId\":\"inv-http\"}"
            );
            UUID sourceDeliveryId = jdbcTemplate.queryForObject(
                    "select id from deliveries where event_id = ?", UUID.class, accepted.eventId()
            );
            jdbcTemplate.update(
                    "update deliveries set state = 'EXHAUSTED', due_at = null, attempt_count = 5, "
                            + "terminal_at = CURRENT_TIMESTAMP where id = ?",
                    sourceDeliveryId
            );

            URI baseUri = baseUri(context);
            HttpClient ownerBrowser = browserClient();
            login(ownerBrowser, baseUri, OWNER_LOGIN);
            URI eventList = baseUri.resolve("/api/v1/projects/" + project.id() + "/events");
            URI healthUri = baseUri.resolve("/api/v1/projects/" + project.id() + "/delivery-health");
            HttpResponse<String> health = ownerBrowser.send(
                    HttpRequest.newBuilder(healthUri).GET().build(), BodyHandlers.ofString()
            );
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(health.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(health.body()).contains("\"observedAt\"").contains("\"dueEnabledCount\"")
                    .doesNotContain(project.id().toString()).doesNotContain("receiver").doesNotContain("inv-http");
            HttpResponse<String> list = ownerBrowser.send(
                    HttpRequest.newBuilder(eventList).GET().build(), BodyHandlers.ofString()
            );
            assertThat(list.statusCode()).isEqualTo(200);
            assertThat(list.body()).contains(accepted.eventId().toString()).doesNotContain("payloadJson").doesNotContain("inv-http");

            URI eventDetail = URI.create(eventList + "/" + accepted.eventId());
            HttpResponse<String> detail = ownerBrowser.send(
                    HttpRequest.newBuilder(eventDetail).GET().build(), BodyHandlers.ofString()
            );
            assertThat(detail.statusCode()).isEqualTo(200);
            assertThat(detail.body()).contains("inv-http");

            URI updatesUri = baseUri.resolve("/api/v1/projects/" + project.id() + "/delivery-updates");
            HttpResponse<InputStream> updates = ownerBrowser.send(
                    HttpRequest.newBuilder(updatesUri)
                            .header("Accept", "text/event-stream")
                            .GET()
                            .build(),
                    BodyHandlers.ofInputStream()
            );
            assertThat(updates.statusCode()).isEqualTo(200);
            assertThat(updates.headers().firstValue("Content-Type").orElse("")).contains("text/event-stream");
            assertThat(updates.headers().firstValue("Cache-Control")).hasValue("no-store");
            awaitListenerConnection(meterRegistry);
            restartDedicatedListener(jdbcTemplate, meterRegistry);
            try (InputStream updatesBody = updates.body();
                 BufferedReader updatesReader = new BufferedReader(new InputStreamReader(updatesBody, StandardCharsets.UTF_8));
                 var readerExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
                assertThat(updatesReader.readLine()).isEqualTo(":connected");

                PublishEventResult liveUpdateEvent = eventPublisher.publish(
                        project.id(), "history-http-live-update", "invoice.paid", "{\"invoiceId\":\"inv-live\"}"
                );
                ClaimedDelivery claim = deliveryClaimer.claim(1, Duration.ofSeconds(15)).getFirst();
                try (DispatchInstruction instruction = attemptStarter.start(claim, Duration.ofSeconds(20)).orElseThrow();
                     DispatchObservation observation = DispatchObservation.httpResponse(
                             DispatchObservation.Outcome.SUCCEEDED,
                             204,
                             Duration.ofMillis(10),
                             new byte[0],
                             false
                     )) {
                    assertThat(instruction.eventId()).isEqualTo(liveUpdateEvent.eventId());
                    finalizer.finalizeAttempt(instruction, observation);
                    var line = readerExecutor.submit(() -> nextEventLine(updatesReader));
                    assertThat(line.get(5, TimeUnit.SECONDS)).isEqualTo("event:delivery.changed");
                    assertThat(updatesReader.readLine())
                            .contains("\"projectId\":\"" + project.id() + "\"")
                            .contains("\"deliveryId\":\"" + instruction.deliveryId() + "\"")
                            .doesNotContain("SUCCEEDED")
                            .doesNotContain("inv-live");
                }
            }

            HttpClient otherBrowser = browserClient();
            login(otherBrowser, baseUri, OTHER_OWNER_LOGIN);
            HttpResponse<String> crossOwner = otherBrowser.send(
                    HttpRequest.newBuilder(eventDetail).GET().build(), BodyHandlers.ofString()
            );
            assertProblem(crossOwner, 404, "RESOURCE_NOT_FOUND");
            HttpResponse<String> crossOwnerHealth = otherBrowser.send(
                    HttpRequest.newBuilder(healthUri).GET().build(), BodyHandlers.ofString()
            );
            assertProblem(crossOwnerHealth, 404, "RESOURCE_NOT_FOUND");
            HttpResponse<String> crossOwnerUpdates = otherBrowser.send(
                    HttpRequest.newBuilder(updatesUri).GET().build(),
                    BodyHandlers.ofString()
            );
            assertProblem(crossOwnerUpdates, 404, "RESOURCE_NOT_FOUND");
            assertThat(otherOwnerId).isNotEqualTo(ownerId);

            URI replayUri = baseUri.resolve(
                    "/api/v1/projects/" + project.id() + "/deliveries/" + sourceDeliveryId + "/replays"
            );
            HttpResponse<String> csrfRejected = ownerBrowser.send(
                    HttpRequest.newBuilder(replayUri)
                            .header("Idempotency-Key", "history-http-replay")
                            .POST(BodyPublishers.noBody())
                            .build(),
                    BodyHandlers.ofString()
            );
            assertProblem(csrfRejected, 403, "CSRF_REJECTED");

            String csrfToken = csrfToken(ownerBrowser, baseUri);
            HttpResponse<String> replay = replay(ownerBrowser, replayUri, csrfToken, "history-http-replay");
            assertThat(replay.statusCode()).isEqualTo(202);
            assertThat(replay.body()).contains("\"idempotentReplay\":false").contains(sourceDeliveryId.toString());
            HttpResponse<String> idempotentReplay = replay(ownerBrowser, replayUri, csrfToken, "history-http-replay");
            assertThat(idempotentReplay.statusCode()).isEqualTo(202);
            assertThat(idempotentReplay.body()).contains("\"idempotentReplay\":true");
        }
    }

    private HttpResponse<String> replay(HttpClient client, URI uri, String csrfToken, String idempotencyKey) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri)
                        .header("X-CSRF-TOKEN", csrfToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .POST(BodyPublishers.noBody())
                        .build(),
                BodyHandlers.ofString()
        );
    }

    private HttpClient browserClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private void login(HttpClient client, URI baseUri, String loginName) throws Exception {
        String csrfToken = csrfToken(client, baseUri);
        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/session"))
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrfToken)
                        .POST(BodyPublishers.ofString(
                                "{\"loginName\":\"" + loginName + "\",\"password\":\"" + PASSWORD + "\"}"
                        ))
                        .build(),
                BodyHandlers.ofString()
        );
        assertThat(login.statusCode()).isEqualTo(200);
    }

    private String csrfToken(HttpClient client, URI baseUri) throws Exception {
        HttpResponse<String> csrf = client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/csrf")).GET().build(),
                BodyHandlers.ofString()
        );
        assertThat(csrf.statusCode()).isEqualTo(200);
        Matcher token = CSRF_TOKEN.matcher(csrf.body());
        assertThat(token.find()).isTrue();
        return token.group(1);
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
    }

    private static void awaitListenerConnection(MeterRegistry meterRegistry) throws InterruptedException {
        awaitCounterAtLeast(meterRegistry, "relayforge.dashboard_updates.listener", "connected", 1);
    }

    private static void restartDedicatedListener(JdbcTemplate jdbcTemplate, MeterRegistry meterRegistry) throws InterruptedException {
        double initialConnections = counterValue(meterRegistry, "relayforge.dashboard_updates.listener", "connected");
        Integer listenerPid = jdbcTemplate.queryForObject(
                "select pid from pg_stat_activity where datname = current_database() "
                        + "and query = 'listen relayforge_delivery_updates' and pid <> pg_backend_pid() "
                        + "order by backend_start desc limit 1",
                Integer.class
        );
        assertThat(listenerPid).isNotNull();
        Boolean terminated = jdbcTemplate.queryForObject("select pg_terminate_backend(?)", Boolean.class, listenerPid);
        assertThat(terminated).isTrue();

        awaitCounterAtLeast(meterRegistry, "relayforge.dashboard_updates.listener", "reconnect", 1);
        awaitCounterAtLeast(meterRegistry, "relayforge.dashboard_updates.listener", "connected", initialConnections + 1);
    }

    private static void awaitCounterAtLeast(
            MeterRegistry meterRegistry,
            String name,
            String outcome,
            double expectedMinimum
    ) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (counterValue(meterRegistry, name, outcome) >= expectedMinimum) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Counter " + name + " outcome=" + outcome + " did not reach " + expectedMinimum);
    }

    private static double counterValue(MeterRegistry meterRegistry, String name, String outcome) {
        var counter = meterRegistry.find(name).tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    private static String nextEventLine(BufferedReader reader) throws Exception {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith("event:")) {
                return line;
            }
        }
        throw new AssertionError("SSE stream closed before delivery.changed");
    }

    private OwnerBootstrapResult bootstrap(OwnerBootstrap ownerBootstrap, String loginName) {
        char[] password = PASSWORD.toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(RelayForgeApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(Map.of(
                        "relayforge.runtime", "api",
                        "spring.datasource.url", POSTGRES.getJdbcUrl(),
                        "spring.datasource.username", POSTGRES.getUsername(),
                        "spring.datasource.password", POSTGRES.getPassword(),
                        "server.port", "0",
                        "spring.lifecycle.timeout-per-shutdown-phase", "1s"
                ))
                .run();
    }

    private static URI baseUri(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://localhost:" + port);
    }
}
