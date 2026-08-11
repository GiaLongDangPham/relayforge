package com.gialong.relayforge.runtime.project;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
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
import java.util.Map;

import static java.net.http.HttpRequest.BodyPublishers;
import static java.net.http.HttpResponse.BodyHandlers;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ProjectHttpIntegrationTests {

    private static final String FIRST_LOGIN = "project.http.first";
    private static final String SECOND_LOGIN = "project.http.second";
    private static final String PASSWORD = "project-http-test-password";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_project_http_test")
            .withUsername("relayforge_project_http_test")
            .withPassword("relayforge_project_http_test");

    @Test
    void ownerSessionScopesProjectsAndCsrfAndVersionProtectMutations() throws Exception {
        try (ConfigurableApplicationContext context = startApplication()) {
            OwnerBootstrap ownerBootstrap = context.getBean(OwnerBootstrap.class);
            bootstrap(ownerBootstrap, SECOND_LOGIN);
            URI baseUri = baseUri(context);
            HttpClient firstOwner = browserClient();
            HttpClient secondOwner = browserClient();

            String firstCsrf = login(firstOwner, baseUri, FIRST_LOGIN);
            HttpResponse<String> created = postJson(
                    firstOwner,
                    baseUri.resolve("/api/v1/projects"),
                    "{\"name\":\"Payments\"}",
                    firstCsrf
            );
            assertThat(created.statusCode()).isEqualTo(201);
            JsonNode createdProject = JSON.readTree(created.body());
            String projectId = createdProject.get("id").asString();
            assertThat(created.headers().firstValue("Location"))
                    .contains("/api/v1/projects/" + projectId);

            assertThat(postJson(
                    firstOwner,
                    baseUri.resolve("/api/v1/projects"),
                    "{\"name\":\"Operations\"}",
                    firstCsrf
            ).statusCode()).isEqualTo(201);
            JsonNode firstPage = JSON.readTree(get(firstOwner, baseUri.resolve("/api/v1/projects?limit=1")).body());
            String nextCursor = firstPage.get("nextCursor").asString();
            JsonNode secondPage = JSON.readTree(get(
                    firstOwner,
                    baseUri.resolve("/api/v1/projects?limit=1&cursor=" + nextCursor)
            ).body());
            assertThat(firstPage.get("items").size()).isEqualTo(1);
            assertThat(secondPage.get("items").size()).isEqualTo(1);
            assertThat(firstPage.get("items").get(0).get("id").asString())
                    .isNotEqualTo(secondPage.get("items").get(0).get("id").asString());

            String secondCsrf = login(secondOwner, baseUri, SECOND_LOGIN);
            HttpResponse<String> crossOwnerRead = get(secondOwner, baseUri.resolve("/api/v1/projects/" + projectId));
            assertThat(crossOwnerRead.statusCode()).isEqualTo(404);
            assertThat(crossOwnerRead.body()).contains("RESOURCE_NOT_FOUND");

            assertThat(patchJson(
                    firstOwner,
                    baseUri.resolve("/api/v1/projects/" + projectId),
                    "{\"name\":\"Renamed\",\"version\":0}",
                    null
            ).statusCode()).isEqualTo(403);
            HttpResponse<String> renamed = patchJson(
                    firstOwner,
                    baseUri.resolve("/api/v1/projects/" + projectId),
                    "{\"name\":\"Renamed\",\"version\":0}",
                    firstCsrf
            );
            assertThat(renamed.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(renamed.body()).get("version").asLong()).isEqualTo(1);
            HttpResponse<String> staleRename = patchJson(
                    firstOwner,
                    baseUri.resolve("/api/v1/projects/" + projectId),
                    "{\"name\":\"Stale\",\"version\":0}",
                    firstCsrf
            );
            assertThat(staleRename.statusCode()).isEqualTo(409);
            assertThat(staleRename.body()).contains("OPTIMISTIC_LOCK_CONFLICT");
            assertThat(secondCsrf).isNotBlank();
        }
    }

    private HttpClient browserClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private String login(HttpClient client, URI baseUri, String loginName) throws Exception {
        String csrf = csrf(client, baseUri);
        HttpResponse<String> response = postJson(
                client,
                baseUri.resolve("/api/v1/auth/session"),
                "{\"loginName\":\"" + loginName + "\",\"password\":\"" + PASSWORD + "\"}",
                csrf
        );
        assertThat(response.statusCode()).isEqualTo(200);
        return csrf;
    }

    private String csrf(HttpClient client, URI baseUri) throws Exception {
        HttpResponse<String> response = get(client, baseUri.resolve("/api/v1/auth/csrf"));
        assertThat(response.statusCode()).isEqualTo(200);
        return JSON.readTree(response.body()).get("token").asString();
    }

    private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
        return client.send(HttpRequest.newBuilder(uri).GET().build(), BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(HttpClient client, URI uri, String body, String csrfToken) throws Exception {
        return sendJson(client, uri, "POST", body, csrfToken);
    }

    private HttpResponse<String> patchJson(HttpClient client, URI uri, String body, String csrfToken) throws Exception {
        return sendJson(client, uri, "PATCH", body, csrfToken);
    }

    private HttpResponse<String> sendJson(HttpClient client, URI uri, String method, String body, String csrfToken)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .header("Content-Type", "application/json")
                .method(method, BodyPublishers.ofString(body));
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        return client.send(request.build(), BodyHandlers.ofString());
    }

    private void bootstrap(OwnerBootstrap ownerBootstrap, String loginName) {
        char[] password = PASSWORD.toCharArray();
        try {
            ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(RelayForgeApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(Map.of(
                        "relayforge.runtime", "api",
                        "relayforge.bootstrap.owner.enabled", "true",
                        "relayforge.bootstrap.owner.login-name", FIRST_LOGIN,
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
}
