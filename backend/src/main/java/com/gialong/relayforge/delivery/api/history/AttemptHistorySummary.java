package com.gialong.relayforge.delivery.api.history;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Owner-safe attempt summary. Response preview and destination fingerprint require a detail lookup. */
public record AttemptHistorySummary(
        UUID id,
        short attemptNumber,
        AttemptHistoryStatus status,
        Instant startedAt,
        Instant finishedAt,
        Integer httpStatus,
        String failureCode,
        Integer latencyMilliseconds
) {

    public AttemptHistorySummary {
        Objects.requireNonNull(id, "id must not be null");
        if (attemptNumber < 1 || attemptNumber > 5) {
            throw new IllegalArgumentException("attemptNumber must be between one and five");
        }
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        if (latencyMilliseconds != null && latencyMilliseconds < 0) {
            throw new IllegalArgumentException("latencyMilliseconds must not be negative");
        }
    }
}
