package com.gialong.relayforge.delivery.application;

/** The bounded input that determined a persisted retry delay, never a raw receiver header. */
public enum RetryScheduleSource {
    BACKOFF,
    RETRY_AFTER,
    ENDPOINT_POLICY
}
