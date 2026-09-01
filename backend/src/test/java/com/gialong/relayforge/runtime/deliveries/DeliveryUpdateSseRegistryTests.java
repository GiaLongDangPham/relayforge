package com.gialong.relayforge.runtime.deliveries;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryUpdateSseRegistryTests {

    @Test
    void shutdownClosesTheBoundedStreamAndRecordsOneCloseOutcome() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DeliveryUpdateSseRegistry registry = new DeliveryUpdateSseRegistry(meterRegistry);
        registry.start();

        var emitter = registry.open(UUID.randomUUID());
        assertThat(emitter.getTimeout()).isEqualTo(Duration.ofMinutes(15).toMillis());

        registry.stop();

        assertThat(meterRegistry.find("relayforge.dashboard_updates.streams")
                .tag("outcome", "closed")
                .counter()
                .count()).isEqualTo(1);
    }
}
