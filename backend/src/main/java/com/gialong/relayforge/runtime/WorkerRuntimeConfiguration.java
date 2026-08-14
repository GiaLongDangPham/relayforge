package com.gialong.relayforge.runtime;

import com.gialong.relayforge.delivery.api.DeliveryClaimer;
import com.gialong.relayforge.delivery.api.OutboundWebhookDispatcher;
import com.gialong.relayforge.delivery.api.OutboundWebhookMessageSigner;
import com.gialong.relayforge.runtime.worker.OutboundDispatchProperties;
import com.gialong.relayforge.runtime.worker.PinnedOutboundWebhookDispatcher;
import com.gialong.relayforge.runtime.worker.WorkerClaimCoordinator;
import com.gialong.relayforge.runtime.worker.WorkerProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "worker")
@EnableConfigurationProperties({WorkerProperties.class, OutboundDispatchProperties.class})
class WorkerRuntimeConfiguration {

    @Bean
    @ConditionalOnBean(DeliveryClaimer.class)
    WorkerClaimCoordinator workerClaimCoordinator(DeliveryClaimer deliveryClaimer, WorkerProperties properties) {
        return new WorkerClaimCoordinator(deliveryClaimer, properties);
    }

    @Bean(destroyMethod = "close")
    OutboundWebhookDispatcher outboundWebhookDispatcher(
            OutboundWebhookMessageSigner messageSigner,
            OutboundDispatchProperties properties,
            @Value("${relayforge.security.production:false}") boolean production,
            @Value("${relayforge.endpoint.allow-local-http:false}") boolean allowLocalHttp
    ) {
        return new PinnedOutboundWebhookDispatcher(messageSigner, production, allowLocalHttp, properties);
    }
}
