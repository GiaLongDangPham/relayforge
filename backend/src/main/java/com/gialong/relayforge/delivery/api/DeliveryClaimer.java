package com.gialong.relayforge.delivery.api;

import java.time.Duration;
import java.util.List;

/**
 * Worker-facing durable claim and pre-attempt recovery contract.
 */
public interface DeliveryClaimer {

    List<ClaimedDelivery> claim(int requestedCapacity, Duration initialLease);

    int recoverExpiredPreAttemptClaims(int recoveryCapacity);
}
