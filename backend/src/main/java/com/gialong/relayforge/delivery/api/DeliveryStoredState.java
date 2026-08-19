package com.gialong.relayforge.delivery.api;

/** Durable delivery state as stored by the delivery state machine. */
public enum DeliveryStoredState {
    PENDING,
    CLAIMED,
    SUCCEEDED,
    FAILED_PERMANENT,
    EXHAUSTED
}
