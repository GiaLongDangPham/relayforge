package com.gialong.relayforge.runtime.publisher;

import com.gialong.relayforge.delivery.api.publish.PublisherQuotaSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Bounded API admission setting; PostgreSQL owns the actual durable counter. */
@ConfigurationProperties(prefix = "relayforge.publisher.quota")
public record PublisherQuotaProperties(@DefaultValue("10000") int dailyAcceptedEvents) {

    public PublisherQuotaProperties {
        new PublisherQuotaSettings(dailyAcceptedEvents);
    }
}
