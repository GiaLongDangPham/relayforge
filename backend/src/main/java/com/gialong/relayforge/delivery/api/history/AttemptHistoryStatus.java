package com.gialong.relayforge.delivery.api.history;

public enum AttemptHistoryStatus {
    STARTED,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    UNKNOWN
}
