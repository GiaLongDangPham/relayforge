package com.gialong.relayforge.runtime.publisher;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublisherQuotaPropertiesTests {

    @Test
    void acceptsTheBoundedDailyDefault() {
        PublisherQuotaProperties properties = new PublisherQuotaProperties(10_000);

        assertThat(properties.dailyAcceptedEvents()).isEqualTo(10_000);
    }

    @Test
    void rejectsNonpositiveAndUnboundedDailyLimits() {
        assertThatThrownBy(() -> new PublisherQuotaProperties(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dailyAcceptedEvents");
        assertThatThrownBy(() -> new PublisherQuotaProperties(1_000_001))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dailyAcceptedEvents");
    }
}
