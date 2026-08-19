package com.gialong.relayforge.delivery.api;

/** Owner-visible derived delivery status. It is never a write input or a persisted state. */
public enum DeliveryDisplayStatus {
    PENDING,
    CLAIMED,
    RETRY_SCHEDULED,
    PAUSED,
    SUCCEEDED,
    FAILED_PERMANENT,
    EXHAUSTED
}
