package com.gialong.relayforge.delivery.application;

public enum AttemptStatus {
    SUCCEEDED,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
    UNKNOWN
}
