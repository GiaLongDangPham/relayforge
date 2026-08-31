package com.gialong.relayforge.delivery.application;

/** Durable endpoint circuit states from ADR-009. */
public enum EndpointCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
