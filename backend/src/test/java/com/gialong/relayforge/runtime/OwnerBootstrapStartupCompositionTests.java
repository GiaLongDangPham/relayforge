package com.gialong.relayforge.runtime;

import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapOutcome;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerBootstrapStartupCompositionTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ApiRuntimeConfiguration.class)
            .withBean(OwnerBootstrap.class, () -> (loginName, plaintextPassword) ->
                    new OwnerBootstrapResult(
                            UUID.randomUUID(),
                            loginName,
                            OwnerBootstrapOutcome.CREATED
                    ));

    @Test
    void disabledApiConfigurationCreatesNoStartupRunner() {
        contextRunner
                .withPropertyValues("relayforge.runtime=api")
                .run(context -> assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty());
    }

    @Test
    void enabledApiConfigurationCreatesOneStartupRunner() {
        contextRunner
                .withPropertyValues(
                        "relayforge.runtime=api",
                        "relayforge.bootstrap.owner.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ApplicationRunner.class);
                    assertThat(context).hasSingleBean(OwnerBootstrapStartupRunner.class);
                });
    }

    @Test
    void workerModeCannotCreateStartupRunnerEvenWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "relayforge.runtime=worker",
                        "relayforge.bootstrap.owner.enabled=true"
                )
                .run(context -> assertThat(context.getBeansOfType(ApplicationRunner.class)).isEmpty());
    }
}
