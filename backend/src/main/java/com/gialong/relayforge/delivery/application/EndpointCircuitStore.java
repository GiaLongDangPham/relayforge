package com.gialong.relayforge.delivery.application;

import java.util.Optional;
import java.util.UUID;

/** Delivery-owned persistence boundary for durable endpoint circuit state. */
public interface EndpointCircuitStore {

    /** A missing row is semantically {@link EndpointCircuitState#CLOSED}. */
    Optional<EndpointCircuit> findByEndpointId(UUID endpointId);
}
