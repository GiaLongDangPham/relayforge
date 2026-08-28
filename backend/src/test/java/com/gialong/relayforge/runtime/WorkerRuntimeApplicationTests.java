package com.gialong.relayforge.runtime;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.OutboundWebhookDispatcher;
import com.gialong.relayforge.runtime.security.OwnerAuthenticationProvider;
import com.gialong.relayforge.runtime.worker.WorkerClaimCoordinator;
import com.gialong.relayforge.runtime.worker.DeliveryWorkerLoop;
import com.gialong.relayforge.runtime.worker.TerminalHistoryRetentionLoop;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusScrapeEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.session.SessionRepository;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class WorkerRuntimeApplicationTests {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_worker_test")
            .withUsername("relayforge_worker_test")
            .withPassword("relayforge_worker_test");

    @Test
    void packagedWorkerLauncherStartsManagementOnlyServletContextWithoutApiAdaptersOrSessions() {
        SpringApplication application = RelayForgeApplication.createApplication();
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(Map.of(
                "relayforge.runtime", "worker",
                "server.port", "0",
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword(),
                "relayforge.endpoint-secret.encryption-key", "MDEyMzQ1Njc4OUFCQ0RFRjAxMjM0NTY3ODlBQkNERUY"
        ));

        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(context).isInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBean(RelayForgeRuntimeProperties.class).runtime()).isEqualTo(RuntimeMode.WORKER);
            assertThat(context.getBeansOfType(ApiRuntimeConfiguration.class)).isEmpty();
            assertThat(context.getBeansOfType(WorkerRuntimeConfiguration.class)).hasSize(1);
            assertThat(context.getBeansOfType(WorkerClaimCoordinator.class)).hasSize(1);
            assertThat(context.getBeansOfType(DeliveryWorkerLoop.class)).hasSize(1);
            assertThat(context.getBeansOfType(TerminalHistoryRetentionLoop.class)).hasSize(1);
            assertThat(context.getBeansOfType(OutboundWebhookDispatcher.class)).hasSize(1);
            assertThat(context.getBeansOfType(OwnerBootstrapStartupRunner.class)).isEmpty();
            assertThat(context.getBeansOfType(OwnerAuthenticationProvider.class)).isEmpty();
            assertThat(context.getBeansOfType(PrometheusMeterRegistry.class)).hasSize(1);
            assertThat(context.getBeansOfType(PrometheusScrapeEndpoint.class)).hasSize(1);
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).hasSize(2);
            assertThat(context.getBeansOfType(SessionRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(SessionRepositoryFilter.class)).isEmpty();
            assertThat(context.getBeansWithAnnotation(RestController.class)).isEmpty();
            assertThat(context.getBeansWithAnnotation(Controller.class).keySet()).containsOnly("basicErrorController");

            int managementPort = ((WebServerApplicationContext) context).getWebServer().getPort();
            assertThat(get(managementPort, "/actuator/health/readiness").statusCode()).isEqualTo(200);
            HttpResponse<String> prometheusResponse = get(managementPort, "/actuator/prometheus");
            assertThat(prometheusResponse.statusCode()).isEqualTo(200);
            assertThat(prometheusResponse.body())
                    .contains("relayforge_worker_running")
                    .contains("relayforge_worker_permits_available")
                    .contains("relayforge_retention_runs");
            assertThat(get(managementPort, "/api/v1/auth/csrf").statusCode()).isEqualTo(403);

            context.getBean(DeliveryWorkerLoop.class).stop();
        }
    }

    private static HttpResponse<String> get(int port, String path) {
        try {
            return HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        } catch (Exception exception) {
            throw new AssertionError("Could not call worker management endpoint", exception);
        }
    }

}
