package com.gialong.relayforge.runtime;

import com.gialong.relayforge.RelayForgeApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = {
                "relayforge.runtime=worker",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class WorkerRuntimeApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private RelayForgeRuntimeProperties runtimeProperties;

    @Test
    void realApplicationActivatesOnlyWorkerComposition() {
        assertThat(runtimeProperties.runtime()).isEqualTo(RuntimeMode.WORKER);
        assertThat(applicationContext.getBeansOfType(ApiRuntimeConfiguration.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(WorkerRuntimeConfiguration.class)).hasSize(1);
    }

}
