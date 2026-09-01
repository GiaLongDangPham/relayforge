package com.gialong.relayforge.delivery.application;

import java.util.Objects;
import java.util.UUID;

/**
 * A row locked by an expired-lease recovery transaction; it is not a public history result.
 */
public record ExpiredStartedAttempt(
        UUID projectId,
        UUID endpointId,
        UUID deliveryId,
        UUID attemptId,
        UUID claimToken,
        int attemptNumber
) {

    public ExpiredStartedAttempt {
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        Objects.requireNonNull(attemptId, "attemptId must not be null");
        Objects.requireNonNull(claimToken, "claimToken must not be null");
        if (attemptNumber < 1 || attemptNumber > 5) {
            throw new IllegalArgumentException("attemptNumber must be between one and five");
        }
    }
}
