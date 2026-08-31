package com.gialong.relayforge.runtime;

import com.gialong.relayforge.delivery.api.DeliveryClaimer;
import com.gialong.relayforge.delivery.api.DeliveryAttemptFinalizer;
import com.gialong.relayforge.delivery.api.DeliveryAttemptRecovery;
import com.gialong.relayforge.delivery.api.DeliveryAttemptStarter;
import com.gialong.relayforge.delivery.api.OutboundWebhookDispatcher;
import com.gialong.relayforge.delivery.api.OutboundWebhookMessageSigner;
import com.gialong.relayforge.delivery.api.TerminalHistoryRetention;
import com.gialong.relayforge.runtime.worker.OutboundDispatchProperties;
import com.gialong.relayforge.runtime.worker.PinnedOutboundWebhookDispatcher;
import com.gialong.relayforge.runtime.worker.WorkerClaimCoordinator;
import com.gialong.relayforge.runtime.worker.DeliveryWorkerLoop;
import com.gialong.relayforge.runtime.worker.WorkerDeliveryProcessor;
import com.gialong.relayforge.runtime.worker.WorkerProperties;
import com.gialong.relayforge.runtime.worker.WorkerOperationalMetrics;
import com.gialong.relayforge.runtime.worker.MicrometerWorkerOperationalMetrics;
import com.gialong.relayforge.runtime.worker.RetentionProperties;
import com.gialong.relayforge.runtime.worker.TerminalHistoryRetentionLoop;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "worker")
@EnableConfigurationProperties({
        WorkerProperties.class,
        OutboundDispatchProperties.class,
        RetentionProperties.class
})
class WorkerRuntimeConfiguration {

    @Bean
    @ConditionalOnBean(DeliveryClaimer.class)
    WorkerClaimCoordinator workerClaimCoordinator(DeliveryClaimer deliveryClaimer, WorkerProperties properties) {
        return new WorkerClaimCoordinator(deliveryClaimer, properties);
    }

    @Bean
    @ConditionalOnBean(WorkerClaimCoordinator.class)
    WorkerOperationalMetrics workerOperationalMetrics(
            MeterRegistry meterRegistry,
            WorkerClaimCoordinator claimCoordinator
    ) {
        return new MicrometerWorkerOperationalMetrics(meterRegistry, claimCoordinator);
    }

    @Bean(destroyMethod = "close")
    OutboundWebhookDispatcher outboundWebhookDispatcher(
            OutboundWebhookMessageSigner messageSigner,
            OutboundDispatchProperties properties,
            WorkerProperties workerProperties,
            @Value("${relayforge.security.production:false}") boolean production,
            @Value("${relayforge.endpoint.allow-local-http:false}") boolean allowLocalHttp
    ) {
        Duration requiredAttemptLease = properties.dispatchDeadline().plusSeconds(10);
        if (workerProperties.attemptExecutionLease().compareTo(requiredAttemptLease) < 0) {
            throw new IllegalArgumentException(
                    "relayforge.worker.attempt-execution-lease must cover dispatch-deadline plus finalization margin and safety cushion"
            );
        }
        return new PinnedOutboundWebhookDispatcher(messageSigner, production, allowLocalHttp, properties);
    }

    @Bean
    WorkerDeliveryProcessor workerDeliveryProcessor(
            DeliveryAttemptStarter attemptStarter,
            OutboundWebhookDispatcher dispatcher,
            DeliveryAttemptFinalizer finalizer,
            WorkerProperties properties,
            WorkerOperationalMetrics metrics
    ) {
        return new WorkerDeliveryProcessor(attemptStarter, dispatcher, finalizer, properties, metrics);
    }

    @Bean
    @ConditionalOnBean({WorkerClaimCoordinator.class, DeliveryAttemptRecovery.class})
    @ConditionalOnProperty(prefix = "relayforge.worker", name = "lifecycle-enabled", havingValue = "true", matchIfMissing = true)
    DeliveryWorkerLoop deliveryWorkerLoop(
            WorkerClaimCoordinator claimCoordinator,
            DeliveryClaimer deliveryClaimer,
            DeliveryAttemptRecovery attemptRecovery,
            WorkerDeliveryProcessor processor,
            WorkerProperties properties,
            WorkerOperationalMetrics metrics
    ) {
        return new DeliveryWorkerLoop(claimCoordinator, deliveryClaimer, attemptRecovery, processor, properties, metrics);
    }

    @Bean
    @ConditionalOnProperty(prefix = "relayforge.retention", name = "enabled", havingValue = "true", matchIfMissing = true)
    TerminalHistoryRetentionLoop terminalHistoryRetentionLoop(
            TerminalHistoryRetention retention,
            RetentionProperties properties,
            WorkerOperationalMetrics metrics
    ) {
        return new TerminalHistoryRetentionLoop(retention, properties, metrics);
    }
}
