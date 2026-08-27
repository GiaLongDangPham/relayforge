package com.gialong.relayforge.runtime.security;

import com.gialong.relayforge.RelayForgeApplication;
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
import java.util.Map;

import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpResponse.BodyHandlers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class OwnerBrowserAuthenticationIntegrationTests {

    private static final String LOGIN_NAME = "Browser.Owner";
    private static final String CANONICAL_LOGIN_NAME = "browser.owner";
    private static final String PASSWORD = "browser-authentication-secret";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_owner_browser_auth_test")
            .withUsername("relayforge_owner_browser_auth_test")
            .withPassword("relayforge_owner_browser_auth_test");

    @Test
    void ownerBrowserFlowEnforcesCsrfPersistsSessionAcrossRestartAndBoundsFailures() throws Exception {
        CookieManager ownerCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient ownerClient = HttpClient.newBuilder()
                .cookieHandler(ownerCookies)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        try (ConfigurableApplicationContext firstContext = startApplication(true)) {
            URI firstBaseUri = baseUri(firstContext);
            assertAllowedCors(ownerClient, firstBaseUri);
            assertRejectedCors(ownerClient, firstBaseUri);
            assertLoginWithoutCsrfRejected(firstBaseUri);

            CsrfToken csrf = csrf(ownerClient, firstBaseUri);
            String anonymousCookie = sessionCookieValue(csrf.response());
            HttpResponse<String> login = postJson(
                    ownerClient,
                    firstBaseUri.resolve("/api/v1/auth/session"),
                    loginJson(PASSWORD),
                    csrf.token()
            );

            assertThat(login.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(login.body()).get("loginName").asString()).isEqualTo(CANONICAL_LOGIN_NAME);
            assertThat(sessionCookieValue(login)).isNotEqualTo(anonymousCookie);
            assertThat(get(ownerClient, firstBaseUri.resolve("/api/v1/auth/me")).statusCode()).isEqualTo(200);
        }

        try (ConfigurableApplicationContext secondContext = startApplication(false)) {
            URI secondBaseUri = baseUri(secondContext);
            assertThat(get(ownerClient, secondBaseUri.resolve("/api/v1/auth/me")).statusCode()).isEqualTo(200);

            assertThat(delete(ownerClient, secondBaseUri.resolve("/api/v1/auth/session"), null).statusCode())
                    .isEqualTo(403);
            CsrfToken csrf = csrf(ownerClient, secondBaseUri);
            HttpResponse<String> logout = delete(ownerClient, secondBaseUri.resolve("/api/v1/auth/session"), csrf.token());
            assertThat(logout.statusCode()).isEqualTo(204);
            assertThat(logout.headers().allValues("Set-Cookie"))
                    .anyMatch(value -> value.startsWith("RF_SESSION=") && value.contains("Max-Age=0"));
            assertThat(get(ownerClient, secondBaseUri.resolve("/api/v1/auth/me")).statusCode()).isEqualTo(401);

            assertRateLimitedAfterFiveFailures(secondBaseUri);
        }
    }

    private void assertLoginWithoutCsrfRejected(URI baseUri) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        assertThat(postJson(client, baseUri.resolve("/api/v1/auth/session"), loginJson(PASSWORD), null)
                .statusCode()).isEqualTo(403);
    }

    private void assertRateLimitedAfterFiveFailures(URI baseUri) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        CsrfToken csrf = csrf(client, baseUri);

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(postJson(
                    client,
                    baseUri.resolve("/api/v1/auth/session"),
                    loginJson("wrong-browser-secret"),
                    csrf.token()
            ).statusCode()).isEqualTo(401);
        }

        HttpResponse<String> rateLimited = postJson(
                client,
                baseUri.resolve("/api/v1/auth/session"),
                loginJson("wrong-browser-secret"),
                csrf.token()
        );
        assertThat(rateLimited.statusCode()).isEqualTo(429);
        assertThat(rateLimited.body()).contains("RATE_LIMITED");
    }

    private void assertAllowedCors(HttpClient client, URI baseUri) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/session"))
                        .method("OPTIONS", BodyPublishers.noBody())
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type, Idempotency-Key")
                        .build(),
                BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Access-Control-Allow-Origin"))
                .contains("http://localhost:5173");
        assertThat(response.headers().firstValue("Access-Control-Allow-Headers"))
                .hasValueSatisfying(headers -> {
                    assertThat(headers).containsIgnoringCase("Authorization");
                    assertThat(headers).containsIgnoringCase("Content-Type");
                    assertThat(headers).containsIgnoringCase("Idempotency-Key");
                });
    }

    private void assertRejectedCors(HttpClient client, URI baseUri) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(baseUri.resolve("/api/v1/auth/session"))
                        .method("OPTIONS", BodyPublishers.noBody())
                        .header("Origin", "https://unapproved.example")
                        .header("Access-Control-Request-Method", "POST")
                        .build(),
                BodyHandlers.ofString()
        );

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private CsrfToken csrf(HttpClient client, URI baseUri) throws Exception {
        HttpResponse<String> response = get(client, baseUri.resolve("/api/v1/auth/csrf"));
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.get("headerName").asString()).isEqualTo("X-CSRF-TOKEN");
        return new CsrfToken(body.get("token").asString(), response);
    }

    private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(HttpClient client, URI uri, String csrfToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).DELETE();
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        return client.send(request.build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(HttpClient client, URI uri, String body, String csrfToken)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body));
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        return client.send(request.build(), BodyHandlers.ofString());
    }

    private String sessionCookieValue(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .filter(value -> value.startsWith("RF_SESSION="))
                .findFirst()
                .map(value -> value.substring(0, value.indexOf(';')))
                .orElseThrow(() -> new IllegalStateException("Missing RF_SESSION cookie: " + response.headers().map()));
    }

    private String loginJson(String password) {
        return "{\"loginName\":\"" + LOGIN_NAME + "\",\"password\":\"" + password + "\"}";
    }

    private ConfigurableApplicationContext startApplication(boolean bootstrapOwner) {
        return new SpringApplicationBuilder(RelayForgeApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(Map.of(
                        "relayforge.runtime", "api",
                        "relayforge.bootstrap.owner.enabled", Boolean.toString(bootstrapOwner),
                        "relayforge.bootstrap.owner.login-name", LOGIN_NAME,
                        "relayforge.bootstrap.owner.password", PASSWORD,
                        "spring.datasource.url", POSTGRES.getJdbcUrl(),
                        "spring.datasource.username", POSTGRES.getUsername(),
                        "spring.datasource.password", POSTGRES.getPassword(),
                        "server.port", "0"
                ))
                .run();
    }

    private URI baseUri(ConfigurableApplicationContext context) {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        return URI.create("http://localhost:" + port);
    }

    private record CsrfToken(String token, HttpResponse<String> response) {
    }
}
