package com.gialong.relayforge.runtime;

import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerCredentialVerifier;
import com.gialong.relayforge.runtime.security.OwnerAuthenticationProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
class ApiRuntimeConfiguration {

    @Bean
    OwnerAuthenticationProvider ownerAuthenticationProvider(OwnerCredentialVerifier credentialVerifier) {
        return new OwnerAuthenticationProvider(credentialVerifier);
    }

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
