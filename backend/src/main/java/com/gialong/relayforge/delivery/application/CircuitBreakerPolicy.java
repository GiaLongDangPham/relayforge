package com.gialong.relayforge.delivery.application;

import com.gialong.relayforge.delivery.api.DispatchObservation;

import java.util.Objects;

/** Exact receiver-evidence classifier from ADR-009; it performs no state transition. */
final class CircuitBreakerPolicy {

    boolean isQualifying(DispatchObservation observation) {
        DispatchObservation requiredObservation = Objects.requireNonNull(observation, "observation must not be null");
        if (requiredObservation.outcome() != DispatchObservation.Outcome.RETRYABLE_FAILURE) {
            return false;
        }
        if (requiredObservation.httpStatus().isPresent()) {
            int httpStatus = requiredObservation.httpStatus().getAsInt();
            return httpStatus == 408 || httpStatus == 429 || (httpStatus >= 500 && httpStatus <= 599);
        }
        return requiredObservation.failureCode()
                .map(code -> code == DispatchObservation.FailureCode.DISPATCH_TIMEOUT
                        || code == DispatchObservation.FailureCode.NETWORK_FAILURE)
                .orElse(false);
    }
}
