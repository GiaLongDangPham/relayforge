package com.gialong.relayforge.delivery.api;

import java.time.Duration;

/**
 * Worker-facing durable completion boundary for an already-started attempt.
 */
public interface DeliveryAttemptFinalizer {

    AttemptFinalizationResult finalizeAttempt(DispatchInstruction instruction, DispatchObservation observation);

    /**
     * Uses PostgreSQL time to decide whether another finalization write may safely begin.
     */
    boolean hasCurrentLease(DispatchInstruction instruction, Duration minimumRemaining);
}
