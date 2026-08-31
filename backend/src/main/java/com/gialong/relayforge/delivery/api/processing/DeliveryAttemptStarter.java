package com.gialong.relayforge.delivery.api.processing;

import java.time.Duration;
import java.util.Optional;

/**
 * Worker-facing durable attempt-start boundary. It never performs outbound network I/O.
 */
public interface DeliveryAttemptStarter {

    /**
     * Returns a committed dispatch instruction only when the supplied claim is still current and eligible.
     */
    Optional<DispatchInstruction> start(ClaimedDelivery claim, Duration attemptExecutionLease);
}
