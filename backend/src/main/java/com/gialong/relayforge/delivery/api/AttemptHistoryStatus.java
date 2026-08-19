package com.gialong.relayforge.delivery.api;

public enum AttemptHistoryStatus {
    STARTED,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    UNKNOWN
}
