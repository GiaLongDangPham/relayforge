package com.gialong.relayforge.delivery.api.operations;

/** Bounded row counts removed by one terminal-history retention run. */
public record RetentionCleanupResult(
        int eventsDeleted,
        int deliveriesDeleted,
        int attemptsDeleted,
        int lateDiagnosticsDeleted,
        int replayRequestsDeleted
) {

    public RetentionCleanupResult {
        if (eventsDeleted < 0 || deliveriesDeleted < 0 || attemptsDeleted < 0
                || lateDiagnosticsDeleted < 0 || replayRequestsDeleted < 0) {
            throw new IllegalArgumentException("retention cleanup counts must not be negative");
        }
    }

    public static RetentionCleanupResult empty() {
        return new RetentionCleanupResult(0, 0, 0, 0, 0);
    }

    public RetentionCleanupResult plus(RetentionCleanupResult other) {
        return new RetentionCleanupResult(
                eventsDeleted + other.eventsDeleted,
                deliveriesDeleted + other.deliveriesDeleted,
                attemptsDeleted + other.attemptsDeleted,
                lateDiagnosticsDeleted + other.lateDiagnosticsDeleted,
                replayRequestsDeleted + other.replayRequestsDeleted
        );
    }

    public boolean deletedAnything() {
        return eventsDeleted + deliveriesDeleted + attemptsDeleted + lateDiagnosticsDeleted + replayRequestsDeleted > 0;
    }
}
