package com.gialong.relayforge.delivery.api.processing;

/**
 * Result of one durable finalization submission. A stale result never changes current delivery state.
 */
public enum AttemptFinalizationResult {
    FINALIZED,
    LATE_DIAGNOSTIC_RECORDED,
    STALE
}
