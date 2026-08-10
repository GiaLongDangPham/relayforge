package com.gialong.relayforge.runtime;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.runtime.security.OwnerAuthenticationProvider;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = {
                "relayforge.runtime=worker",
                "relayforge.bootstrap.owner.enabled=true",
                "spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
class WorkerRuntimeApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private EntityManager entityManager;

    @Autowired
    private RelayForgeRuntimeProperties runtimeProperties;

    @Test
    void realApplicationActivatesOnlyWorkerComposition() {
        assertThat(runtimeProperties.runtime()).isEqualTo(RuntimeMode.WORKER);
        assertThat(applicationContext.getBeansOfType(ApiRuntimeConfiguration.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(WorkerRuntimeConfiguration.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(OwnerBootstrapStartupRunner.class)).isEmpty();
        assertThat(applicationContext.getBeansOfType(OwnerAuthenticationProvider.class)).isEmpty();
    }

}
