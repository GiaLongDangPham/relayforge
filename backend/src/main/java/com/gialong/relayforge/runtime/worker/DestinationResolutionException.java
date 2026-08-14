package com.gialong.relayforge.runtime.worker;

import com.gialong.relayforge.delivery.api.DispatchObservation;

final class DestinationResolutionException extends RuntimeException {

    private final DispatchObservation.Outcome outcome;
    private final DispatchObservation.FailureCode failureCode;

    DestinationResolutionException(
            DispatchObservation.Outcome outcome,
            DispatchObservation.FailureCode failureCode
    ) {
        this.outcome = outcome;
        this.failureCode = failureCode;
    }

    DispatchObservation.Outcome outcome() {
        return outcome;
    }

    DispatchObservation.FailureCode failureCode() {
        return failureCode;
    }
}
