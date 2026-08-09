package com.gialong.relayforge.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeModeTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    RuntimeModeConfiguration.class,
                    ApiRuntimeConfiguration.class,
                    WorkerRuntimeConfiguration.class
            );

    @Test
    void apiModeActivatesOnlyApiComposition() {
        contextRunner
                .withPropertyValues("relayforge.runtime=api")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RelayForgeRuntimeProperties.class);
                    assertThat(context).hasSingleBean(ApiRuntimeConfiguration.class);
                    assertThat(context).doesNotHaveBean(WorkerRuntimeConfiguration.class);
                    assertThat(context.getBean(RelayForgeRuntimeProperties.class).runtime())
                            .isEqualTo(RuntimeMode.API);
                });
    }

    @Test
    void workerModeActivatesOnlyWorkerComposition() {
        contextRunner
                .withPropertyValues("relayforge.runtime=worker")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(RelayForgeRuntimeProperties.class);
                    assertThat(context).doesNotHaveBean(ApiRuntimeConfiguration.class);
                    assertThat(context).hasSingleBean(WorkerRuntimeConfiguration.class);
                    assertThat(context.getBean(RelayForgeRuntimeProperties.class).runtime())
                            .isEqualTo(RuntimeMode.WORKER);
                });
    }

    @Test
    void missingModeFailsStartup() {
        contextRunner.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .rootCause()
                    .hasMessage("relayforge.runtime is required and must be either api or worker");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"combined", "API", "a-p-i", "worker_mode"})
    void unsupportedOrNonCanonicalModeFailsStartup(String value) {
        contextRunner
                .withPropertyValues("relayforge.runtime=" + value)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .hasMessageContaining(value);
                });
    }

}
