package com.gialong.relayforge.runtime.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Objects;

/**
 * Bounded local settings for one outbound dispatch cycle; they never define database lease time.
 */
@ConfigurationProperties(prefix = "relayforge.worker.outbound")
public record OutboundDispatchProperties(
        @DefaultValue("2s") Duration connectionTimeout,
        @DefaultValue("10s") Duration dispatchDeadline,
        @DefaultValue("8192") int responsePreviewBytes
) {

    private static final int MAX_RESPONSE_PREVIEW_BYTES = 8 * 1024;

    public OutboundDispatchProperties {
        connectionTimeout = positive(connectionTimeout, "connection-timeout");
        dispatchDeadline = positive(dispatchDeadline, "dispatch-deadline");
        if (connectionTimeout.compareTo(dispatchDeadline) > 0) {
            throw new IllegalArgumentException("relayforge.worker.outbound.connection-timeout must not exceed dispatch-deadline");
        }
        if (responsePreviewBytes < 0 || responsePreviewBytes > MAX_RESPONSE_PREVIEW_BYTES) {
            throw new IllegalArgumentException(
                    "relayforge.worker.outbound.response-preview-bytes must be between zero and "
                            + MAX_RESPONSE_PREVIEW_BYTES
            );
        }
    }

    private static Duration positive(Duration value, String property) {
        Duration required = Objects.requireNonNull(value, property + " must not be null");
        if (required.isNegative() || required.isZero()) {
            throw new IllegalArgumentException("relayforge.worker.outbound." + property + " must be positive");
        }
        return required;
    }
}
