package com.gialong.relayforge.runtime.deliveries;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.EventPublisher;
import com.gialong.relayforge.delivery.api.PublishEventResult;
import com.gialong.relayforge.endpoint.api.WebhookEndpointCatalog;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

            HttpClient otherBrowser = browserClient();
            login(otherBrowser, baseUri, OTHER_OWNER_LOGIN);
            HttpResponse<String> crossOwner = otherBrowser.send(
                    HttpRequest.newBuilder(eventDetail).GET().build(), BodyHandlers.ofString()
            );
            assertProblem(crossOwner, 404, "RESOURCE_NOT_FOUND");
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
                        "server.port", "0"
                ))
                .run();
    }

    private static URI baseUri(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://localhost:" + port);
    }
}
