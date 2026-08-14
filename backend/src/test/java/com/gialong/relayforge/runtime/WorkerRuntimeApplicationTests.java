package com.gialong.relayforge.runtime;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.OutboundWebhookDispatcher;
import com.gialong.relayforge.runtime.security.OwnerAuthenticationProvider;
import com.gialong.relayforge.runtime.worker.WorkerClaimCoordinator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
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
    void packagedWorkerLauncherStartsANonWebContextWithoutApiSecurityOrSessions() {
        SpringApplication application = RelayForgeApplication.createApplication();
        application.setRegisterShutdownHook(false);
        application.setDefaultProperties(Map.of(
                "relayforge.runtime", "worker",
                "spring.datasource.url", POSTGRES.getJdbcUrl(),
                "spring.datasource.username", POSTGRES.getUsername(),
                "spring.datasource.password", POSTGRES.getPassword()
        ));

        try (ConfigurableApplicationContext context = application.run()) {
            assertThat(application.getWebApplicationType()).isEqualTo(WebApplicationType.NONE);
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBean(RelayForgeRuntimeProperties.class).runtime()).isEqualTo(RuntimeMode.WORKER);
            assertThat(context.getBeansOfType(ApiRuntimeConfiguration.class)).isEmpty();
            assertThat(context.getBeansOfType(WorkerRuntimeConfiguration.class)).hasSize(1);
            assertThat(context.getBeansOfType(WorkerClaimCoordinator.class)).hasSize(1);
            assertThat(context.getBeansOfType(OutboundWebhookDispatcher.class)).hasSize(1);
            assertThat(context.getBeansOfType(OwnerBootstrapStartupRunner.class)).isEmpty();
            assertThat(context.getBeansOfType(OwnerAuthenticationProvider.class)).isEmpty();
            assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
            assertThat(context.getBeansOfType(SessionRepository.class)).isEmpty();
            assertThat(context.getBeansOfType(SessionRepositoryFilter.class)).isEmpty();
            assertThat(context.getBeansWithAnnotation(RestController.class)).isEmpty();
            assertThat(context.getBeansWithAnnotation(Controller.class)).isEmpty();
        }
    }

}
