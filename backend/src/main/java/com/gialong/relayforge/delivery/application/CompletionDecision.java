package com.gialong.relayforge.delivery.application;

import java.time.Duration;
import java.util.Objects;

/**
 * The only normal outcomes allowed to leave a started attempt and its claimed delivery.
 */
public record CompletionDecision(AttemptStatus attemptStatus, DeliveryState deliveryState, Duration retryDelay) {

    public CompletionDecision {
        Objects.requireNonNull(attemptStatus, "attemptStatus must not be null");
        Objects.requireNonNull(deliveryState, "deliveryState must not be null");
        if (deliveryState == DeliveryState.PENDING) {
            if (retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()) {
                throw new IllegalArgumentException("pending delivery requires a positive retry delay");
            }
        } else if (retryDelay != null) {
            throw new IllegalArgumentException("terminal delivery must not have a retry delay");
        }
    }
}
