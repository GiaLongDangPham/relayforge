package com.gialong.relayforge.runtime;

import com.gialong.relayforge.delivery.api.publish.PublisherQuotaSettings;
import com.gialong.relayforge.runtime.publisher.PublisherQuotaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Supplies a runtime-configured quota limit without coupling delivery to Spring binding. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PublisherQuotaProperties.class)
class PublisherQuotaConfiguration {

    @Bean
    PublisherQuotaSettings publisherQuotaSettings(PublisherQuotaProperties properties) {
        return new PublisherQuotaSettings(properties.dailyAcceptedEvents());
    }
}
