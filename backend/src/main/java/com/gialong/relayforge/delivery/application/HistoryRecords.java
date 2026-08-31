package com.gialong.relayforge.delivery.application;
import com.gialong.relayforge.delivery.api.history.AttemptHistoryStatus;
import com.gialong.relayforge.delivery.api.history.AttemptHistorySummary;
import com.gialong.relayforge.delivery.api.history.DeliveryStoredState;

import com.gialong.relayforge.delivery.api.history.AttemptHistoryStatus;
import com.gialong.relayforge.delivery.api.history.AttemptHistorySummary;
import com.gialong.relayforge.delivery.api.history.DeliveryStoredState;
import com.gialong.relayforge.delivery.api.history.EventDeliverySummary;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Package boundary data for the delivery persistence adapter; it never crosses the delivery public API. */
public final class HistoryRecords {

    private HistoryRecords() {
    }

    public record EventRecord(UUID id, String eventType, Instant acceptedAt, String payloadJson, int deliveryCount) {

        public EventRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        if (deliveryCount < 0) {
            throw new IllegalArgumentException("deliveryCount must not be negative");
        }
    }
}

    public record DeliveryRecord(
        UUID id,
        UUID eventId,
        UUID endpointId,
        UUID replayOfDeliveryId,
        DeliveryStoredState state,
        int attemptCount,
        Instant dueAt,
        boolean retryScheduled,
        Instant createdAt,
        Instant terminalAt
) {

        public DeliveryRecord {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(endpointId, "endpointId must not be null");
        Objects.requireNonNull(state, "state must not be null");
        if (attemptCount < 0 || attemptCount > 5) {
            throw new IllegalArgumentException("attemptCount must be between zero and five");
        }
        Objects.requireNonNull(createdAt, "createdAt must not be null");
    }
}

    public record DeliveryDetailRecord(DeliveryRecord delivery, String eventType, AttemptHistorySummary latestAttempt) {

        public DeliveryDetailRecord {
        Objects.requireNonNull(delivery, "delivery must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
    }
}

    public record AttemptDetailRecord(
        AttemptHistorySummary summary,
        short destinationFingerprintVersion,
        byte[] destinationFingerprint,
        byte[] responsePreview,
        boolean responseTruncated,
        LateDiagnosticRecord lateDiagnostic
) {

        public AttemptDetailRecord {
        Objects.requireNonNull(summary, "summary must not be null");
        if (destinationFingerprintVersion < 1) {
            throw new IllegalArgumentException("destinationFingerprintVersion must be positive");
        }
        destinationFingerprint = Arrays.copyOf(
                Objects.requireNonNull(destinationFingerprint, "destinationFingerprint must not be null"),
                destinationFingerprint.length
        );
        responsePreview = responsePreview == null ? null : Arrays.copyOf(responsePreview, responsePreview.length);
    }

    @Override
    public byte[] destinationFingerprint() {
        return Arrays.copyOf(destinationFingerprint, destinationFingerprint.length);
    }

    @Override
    public byte[] responsePreview() {
        return responsePreview == null ? null : Arrays.copyOf(responsePreview, responsePreview.length);
    }
}

    public record LateDiagnosticRecord(
        AttemptHistoryStatus observedStatus,
        Integer httpStatus,
        String failureCode,
        Integer latencyMilliseconds,
        Instant observedAt
) {

        public LateDiagnosticRecord {
        Objects.requireNonNull(observedStatus, "observedStatus must not be null");
        Objects.requireNonNull(observedAt, "observedAt must not be null");
    }
}

    public record ReplayResult(ReplayOutcome outcome, com.gialong.relayforge.delivery.api.replay.ReplayDeliveryResult replay) {

        public ReplayResult {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if ((outcome == ReplayOutcome.CREATED || outcome == ReplayOutcome.EXISTING)
                && replay == null) {
            throw new IllegalArgumentException("successful replay outcome requires a replay result");
        }
    }
}

    public enum ReplayOutcome {
    CREATED,
    EXISTING,
    CONFLICT,
    SOURCE_NOT_FOUND,
    SOURCE_NOT_EXHAUSTED
}
}
