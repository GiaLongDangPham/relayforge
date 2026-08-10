package com.gialong.relayforge.runtime;

import com.gialong.relayforge.identity.api.OwnerBootstrap;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
class ApiRuntimeConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "relayforge.bootstrap.owner",
            name = "enabled",
            havingValue = "true"
    )
    ApplicationRunner ownerBootstrapStartupRunner(
            OwnerBootstrap ownerBootstrap,
            Environment environment
    ) {
        return new OwnerBootstrapStartupRunner(ownerBootstrap, environment);
    }
}
