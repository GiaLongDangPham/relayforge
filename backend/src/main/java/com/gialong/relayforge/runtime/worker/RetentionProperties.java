package com.gialong.relayforge.runtime.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

/** Conservative worker-only controls for bounded terminal-history cleanup. */
@ConfigurationProperties(prefix = "relayforge.retention")
public record RetentionProperties(
        @DefaultValue("30") int terminalHistoryDays,
        @DefaultValue("25") int maxGraphsPerRun,
        @DefaultValue("1m") Duration initialDelay,
        @DefaultValue("1h") Duration cleanupInterval
) {

    public RetentionProperties {
        if (terminalHistoryDays <= 0) {
            throw new IllegalArgumentException("relayforge.retention.terminal-history-days must be positive");
        }
        if (maxGraphsPerRun <= 0 || maxGraphsPerRun > 100) {
            throw new IllegalArgumentException("relayforge.retention.max-graphs-per-run must be between 1 and 100");
        }
        initialDelay = nonNegative(initialDelay, "initial-delay");
        cleanupInterval = positive(cleanupInterval, "cleanup-interval");
    }

    private static Duration nonNegative(Duration value, String property) {
        Duration required = Objects.requireNonNull(value, property + " must not be null");
        if (required.isNegative()) {
            throw new IllegalArgumentException("relayforge.retention." + property + " must not be negative");
        }
        return required;
    }

    private static Duration positive(Duration value, String property) {
        Duration required = nonNegative(value, property);
        if (required.isZero()) {
            throw new IllegalArgumentException("relayforge.retention." + property + " must be positive");
        }
        return required;
    }
}
