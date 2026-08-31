package com.gialong.relayforge.runtime.publisher;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.endpoint.api.WebhookEndpointCatalog;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.project.api.CreatedProjectApiKey;
import com.gialong.relayforge.project.api.ProjectApiKeyCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import com.gialong.relayforge.project.api.ProjectCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpResponse.BodyHandlers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PublisherEventHttpIntegrationTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String LOGIN = "publisher.http.owner";
    private static final String PASSWORD = "publisher-http-test-password";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_publisher_http_test")
            .withUsername("relayforge_publisher_http_test")
            .withPassword("relayforge_publisher_http_test");

    @Test
    void publisherBearerAuthenticationAcceptsExactlyOneAtomicIdempotentEventCommand() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            OwnerBootstrap ownerBootstrap = context.getBean(OwnerBootstrap.class);
            ProjectCatalog projectCatalog = context.getBean(ProjectCatalog.class);
            ProjectApiKeyCatalog apiKeyCatalog = context.getBean(ProjectApiKeyCatalog.class);
            WebhookEndpointCatalog endpointCatalog = context.getBean(WebhookEndpointCatalog.class);
            UUID ownerId = bootstrap(ownerBootstrap).ownerId();
            ProjectDetails project = projectCatalog.create(ownerId, "Publisher project");
            ProjectDetails otherProject = projectCatalog.create(ownerId, "Other publisher project");
            CreatedProjectApiKey apiKey = apiKeyCatalog.create(ownerId, project.id(), "Publisher").orElseThrow();
            endpointCatalog.create(
                    ownerId,
                    project.id(),
                    "Invoice receiver",
                    "http://localhost:8080/receiver",
                    List.of("invoice.paid"),
                    true
            ).orElseThrow();
            URI baseUri = baseUri(context);
            URI publishUri = baseUri.resolve("/api/v1/projects/" + project.id() + "/events");
            HttpClient publisher = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

            HttpResponse<String> accepted = publish(
                    publisher,
                    publishUri,
                    apiKey.rawKey(),
                    "invoice-123",
                    "{\"eventType\":\"invoice.paid\",\"payload\":{\"invoiceId\":\"inv-123\",\"amount\":4200}}"
            );
            assertThat(accepted.statusCode()).isEqualTo(202);
            JsonNode acceptedBody = JSON.readTree(accepted.body());
            assertThat(acceptedBody.get("deliveryCount").asInt()).isEqualTo(1);
            assertThat(acceptedBody.get("idempotentReplay").asBoolean()).isFalse();
            assertThat(acceptedBody.has("payload")).isFalse();

            HttpResponse<String> replay = publish(
                    publisher,
                    publishUri,
                    apiKey.rawKey(),
                    "invoice-123",
                    "{\"payload\":{\"amount\":4200,\"invoiceId\":\"inv-123\"},\"eventType\":\"invoice.paid\"}"
            );
            assertThat(replay.statusCode()).isEqualTo(202);
            JsonNode replayBody = JSON.readTree(replay.body());
            assertThat(replayBody.get("eventId").asString()).isEqualTo(acceptedBody.get("eventId").asString());
            assertThat(replayBody.get("idempotentReplay").asBoolean()).isTrue();

            HttpResponse<String> conflict = publish(
                    publisher,
                    publishUri,
                    apiKey.rawKey(),
                    "invoice-123",
                    "{\"eventType\":\"invoice.paid\",\"payload\":{\"invoiceId\":\"inv-123\",\"amount\":4300}}"
            );
            assertThat(conflict.statusCode()).isEqualTo(409);
            assertThat(conflict.body()).contains("IDEMPOTENCY_CONFLICT").doesNotContain("4300");

            assertThat(publish(
                    publisher,
                    baseUri.resolve("/api/v1/projects/" + otherProject.id() + "/events"),
                    apiKey.rawKey(),
                    "other-project",
                    "{\"eventType\":\"invoice.paid\",\"payload\":{}}"
            )).satisfies(response -> assertProblem(response, 403, "PROJECT_KEY_MISMATCH"));
            assertThat(publish(
                    publisher,
                    publishUri,
                    null,
                    "missing-key",
                    "{\"eventType\":\"invoice.paid\",\"payload\":{}}"
            )).satisfies(response -> assertProblem(response, 401, "INVALID_API_KEY"));

            HttpClient ownerBrowser = browserClient();
            login(ownerBrowser, baseUri);
            assertThat(publish(
                    ownerBrowser,
                    publishUri,
                    null,
                    "session-is-not-publisher-auth",
                    "{\"eventType\":\"invoice.paid\",\"payload\":{}}"
            )).satisfies(response -> assertProblem(response, 401, "INVALID_API_KEY"));
            assertThat(publish(
                    publisher,
                    publishUri,
                    apiKey.rawKey(),
                    "too-large",
                    "x".repeat(64 * 1024 + 1)
            )).satisfies(response -> assertProblem(response, 413, "PAYLOAD_TOO_LARGE"));

            assertThat(publish(
                    publisher,
                    publishUri,
                    apiKey.rawKey(),
                    null,
                    "{\"eventType\":\"invoice.paid\",\"payload\":{}}"
            )).satisfies(response -> assertProblem(response, 400, "MISSING_IDEMPOTENCY_KEY"));
            assertThat(publish(
                    publisher,
                    publishUri,
                    apiKey.rawKey(),
                    "malformed-json",
                    "{\"eventType\":"
            )).satisfies(response -> assertProblem(response, 400, "MALFORMED_JSON"));
            assertThat(publish(
                    publisher,
                    publishUri,
                    apiKey.rawKey(),
                    "unknown-property",
                    "{\"eventType\":\"invoice.paid\",\"payload\":{},\"unexpected\":true}"
            )).satisfies(response -> assertProblem(response, 400, "VALIDATION_FAILED"));

            List<CompletableFuture<HttpResponse<String>>> rateLimitBurst = new ArrayList<>();
            for (int attempt = 0; attempt < 100; attempt++) {
                rateLimitBurst.add(publisher.sendAsync(
                        publishRequest(
                                publishUri,
                                apiKey.rawKey(),
                                "rate-limit-" + attempt,
                                "{\"eventType\":"
                        ),
                        BodyHandlers.ofString()
                ));
            }
            CompletableFuture.allOf(rateLimitBurst.toArray(CompletableFuture[]::new)).join();
            HttpResponse<String> rateLimited = rateLimitBurst.stream()
                    .map(CompletableFuture::join)
                    .filter(response -> response.statusCode() == 429)
                    .findFirst()
                    .orElse(null);
            assertThat(rateLimited).isNotNull();
            assertProblem(rateLimited, 429, "PUBLISH_RATE_LIMITED");
            assertThat(rateLimited.headers().firstValue("Retry-After")).contains("1");

            apiKeyCatalog.revoke(ownerId, project.id(), apiKey.apiKey().id()).orElseThrow();
            assertThat(publish(
                    publisher,
                    publishUri,
                    apiKey.rawKey(),
                    "revoked-key",
                    "{\"eventType\":\"invoice.paid\",\"payload\":{}}"
            )).satisfies(response -> assertProblem(response, 401, "INVALID_API_KEY"));
        }
    }

    private HttpResponse<String> publish(
            HttpClient client,
            URI uri,
            String rawKey,
            String idempotencyKey,
            String body
    ) throws Exception {
        return client.send(publishRequest(uri, rawKey, idempotencyKey, body), BodyHandlers.ofString());
    }

    private HttpRequest publishRequest(
            URI uri,
            String rawKey,
            String idempotencyKey,
            String body
    ) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (rawKey != null) {
            request.header("Authorization", "Bearer " + rawKey);
        }
        return request.build();
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":\"" + code + "\"");
    }

    private HttpClient browserClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private void login(HttpClient client, URI baseUri) throws Exception {
        HttpResponse<String> csrf = client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/csrf")).GET().build(),
                BodyHandlers.ofString()
        );
        String csrfToken = JSON.readTree(csrf.body()).get("token").asString();
        HttpResponse<String> login = client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/session"))
                        .header("Content-Type", "application/json")
                        .header("X-CSRF-TOKEN", csrfToken)
                        .POST(BodyPublishers.ofString("{\"loginName\":\"" + LOGIN + "\",\"password\":\"" + PASSWORD + "\"}"))
                        .build(),
                BodyHandlers.ofString()
        );
        assertThat(login.statusCode()).isEqualTo(200);
    }

    private OwnerBootstrapResult bootstrap(OwnerBootstrap ownerBootstrap) {
        char[] password = PASSWORD.toCharArray();
        try {
            return ownerBootstrap.bootstrap(LOGIN, password);
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
