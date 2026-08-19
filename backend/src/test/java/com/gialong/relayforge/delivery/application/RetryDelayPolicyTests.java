package com.gialong.relayforge.delivery.application;

import com.gialong.relayforge.delivery.api.DispatchObservation;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryDelayPolicyTests {

    @Test
    void mapsTransientOutcomesToDocumentedEqualJitterBoundsAndExhaustsTheFifthAttempt() {
        try (AttemptCompletion retryable = AttemptCompletion.observed(DispatchObservation.failure(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                DispatchObservation.FailureCode.NETWORK_FAILURE,
                Duration.ofMillis(10)
        ))) {
            CompletionDecision lowerBound = new RetryDelayPolicy(() -> 0.0d).forObserved(retryable, 1);
            CompletionDecision upperBound = new RetryDelayPolicy(() -> 1.0d).forObserved(retryable, 4);
            CompletionDecision exhausted = new RetryDelayPolicy(() -> 0.5d).forObserved(retryable, 5);

            assertThat(lowerBound.attemptStatus()).isEqualTo(AttemptStatus.RETRYABLE_FAILURE);
            assertThat(lowerBound.deliveryState()).isEqualTo(DeliveryState.PENDING);
            assertThat(lowerBound.retryDelay()).isEqualTo(Duration.ofMillis(2500));
            assertThat(upperBound.retryDelay()).isEqualTo(Duration.ofSeconds(300));
            assertThat(exhausted.deliveryState()).isEqualTo(DeliveryState.EXHAUSTED);
            assertThat(exhausted.retryDelay()).isNull();
        }
    }

    @Test
    void mapsSuccessfulAndPermanentObservationsToTerminalStatesWithoutRetry() {
        try (AttemptCompletion succeeded = AttemptCompletion.observed(DispatchObservation.httpResponse(
                DispatchObservation.Outcome.SUCCEEDED,
                204,
                Duration.ofMillis(12),
                new byte[0],
                false
        )); AttemptCompletion permanent = AttemptCompletion.observed(DispatchObservation.httpResponse(
                DispatchObservation.Outcome.PERMANENT_FAILURE,
                422,
                Duration.ofMillis(12),
                new byte[0],
                false
        ))) {
            RetryDelayPolicy policy = new RetryDelayPolicy(() -> 0.5d);

            assertThat(policy.forObserved(succeeded, 1))
                    .isEqualTo(new CompletionDecision(AttemptStatus.SUCCEEDED, DeliveryState.SUCCEEDED, null));
            assertThat(policy.forObserved(permanent, 1)).isEqualTo(
                    new CompletionDecision(AttemptStatus.PERMANENT_FAILURE, DeliveryState.FAILED_PERMANENT, null)
            );
        }
    }
}
