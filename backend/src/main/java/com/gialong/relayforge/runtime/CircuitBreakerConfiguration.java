package com.gialong.relayforge.runtime;
import com.gialong.relayforge.delivery.api.processing.CircuitBreakerSettings;

import com.gialong.relayforge.delivery.api.processing.CircuitBreakerSettings;
import com.gialong.relayforge.runtime.worker.CircuitBreakerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds worker-operational circuit defaults without making the delivery module depend on runtime code. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CircuitBreakerProperties.class)
class CircuitBreakerConfiguration {

    @Bean
    CircuitBreakerSettings circuitBreakerSettings(CircuitBreakerProperties properties) {
        return new CircuitBreakerSettings(properties.consecutiveFailureThreshold(), properties.openCooldown());
    }
}
