package com.gialong.relayforge.runtime;

import com.gialong.relayforge.delivery.api.DeliveryClaimer;
import com.gialong.relayforge.runtime.worker.WorkerClaimCoordinator;
import com.gialong.relayforge.runtime.worker.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "worker")
@EnableConfigurationProperties(WorkerProperties.class)
class WorkerRuntimeConfiguration {

    @Bean
    @ConditionalOnBean(DeliveryClaimer.class)
    WorkerClaimCoordinator workerClaimCoordinator(DeliveryClaimer deliveryClaimer, WorkerProperties properties) {
        return new WorkerClaimCoordinator(deliveryClaimer, properties);
    }
}
