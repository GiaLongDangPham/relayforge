package com.gialong.relayforge.delivery.application;
import com.gialong.relayforge.delivery.api.processing.DispatchObservation;

import com.gialong.relayforge.delivery.api.processing.DispatchObservation;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerPolicyTests {

    private final CircuitBreakerPolicy policy = new CircuitBreakerPolicy();

    @Test
    void acceptsOnlyDocumentedReceiverFailureEvidence() {
        assertThat(policy.isQualifying(httpRetryable(408))).isTrue();
        assertThat(policy.isQualifying(httpRetryable(429))).isTrue();
        assertThat(policy.isQualifying(httpRetryable(503))).isTrue();
        assertThat(policy.isQualifying(DispatchObservation.failure(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                DispatchObservation.FailureCode.DISPATCH_TIMEOUT,
                Duration.ofMillis(1)
        ))).isTrue();
        assertThat(policy.isQualifying(DispatchObservation.failure(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                DispatchObservation.FailureCode.NETWORK_FAILURE,
                Duration.ofMillis(1)
        ))).isTrue();
    }

    @Test
    void excludesInternalAmbiguityAndNonReceiverFailureEvidence() {
        assertThat(policy.isQualifying(DispatchObservation.failure(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                DispatchObservation.FailureCode.DESTINATION_RESOLUTION_FAILED,
                Duration.ofMillis(1)
        ))).isFalse();
        assertThat(policy.isQualifying(DispatchObservation.httpResponse(
                DispatchObservation.Outcome.SUCCEEDED,
                204,
                Duration.ofMillis(1),
                new byte[0],
                false
        ))).isFalse();
        assertThat(policy.isQualifying(DispatchObservation.httpResponse(
                DispatchObservation.Outcome.PERMANENT_FAILURE,
                404,
                Duration.ofMillis(1),
                new byte[0],
                false
        ))).isFalse();
    }

    private static DispatchObservation httpRetryable(int status) {
        return DispatchObservation.httpResponse(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                status,
                Duration.ofMillis(1),
                new byte[0],
                false
        );
    }
}
