package com.gialong.relayforge.runtime.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CircuitBreakerPropertiesTests {

    @Test
    void acceptsTheBoundedAdrDefaults() {
        CircuitBreakerProperties properties = new CircuitBreakerProperties(3, Duration.ofSeconds(30), 1);

        assertThat(properties.consecutiveFailureThreshold()).isEqualTo(3);
        assertThat(properties.openCooldown()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.halfOpenProbeLimit()).isOne();
    }

    @Test
    void rejectsUnsafeThresholdCooldownAndProbeLimit() {
        assertThatThrownBy(() -> new CircuitBreakerProperties(0, Duration.ofSeconds(30), 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("consecutive-failure-threshold");
        assertThatThrownBy(() -> new CircuitBreakerProperties(3, Duration.ZERO, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("open-cooldown");
        assertThatThrownBy(() -> new CircuitBreakerProperties(3, Duration.ofSeconds(30), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("half-open-probe-limit");
    }
}
