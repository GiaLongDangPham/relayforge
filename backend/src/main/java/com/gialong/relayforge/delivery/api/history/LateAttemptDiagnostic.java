package com.gialong.relayforge.delivery.api.history;

import java.time.Instant;
import java.util.Objects;

/** Immutable stale-worker observation recorded after an attempt was recovered as UNKNOWN. */
public record LateAttemptDiagnostic(
        AttemptHistoryStatus observedStatus,
        Integer httpStatus,
        String failureCode,
        Integer latencyMilliseconds,
        Instant observedAt
) {

    public LateAttemptDiagnostic {
        Objects.requireNonNull(observedStatus, "observedStatus must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
        if (latencyMilliseconds != null && latencyMilliseconds < 0) {
            throw new IllegalArgumentException("latencyMilliseconds must not be negative");
        }
    }
}
