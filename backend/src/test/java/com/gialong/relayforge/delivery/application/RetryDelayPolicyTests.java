package com.gialong.relayforge.delivery.application;

import com.gialong.relayforge.delivery.api.DispatchObservation;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class RetryDelayPolicyTests {

    @Test
    void selectsTheGreaterOfBackoffAndEligibleReceiverHintAndExhaustsTheFifthAttempt() {
        try (AttemptCompletion receiverDeferred = AttemptCompletion.observed(DispatchObservation.httpResponse(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                429,
                Duration.ofMillis(10),
                new byte[0],
                false,
                Optional.of(Duration.ofSeconds(45))
        ))) {
            CompletionDecision receiverSelected = new RetryDelayPolicy(() -> 0.0d).forObserved(receiverDeferred, 1);
            CompletionDecision backoffSelected = new RetryDelayPolicy(() -> 1.0d).forObserved(receiverDeferred, 4);
            CompletionDecision exhausted = new RetryDelayPolicy(() -> 0.5d).forObserved(receiverDeferred, 5);

            assertThat(receiverSelected).isEqualTo(new CompletionDecision(
                    AttemptStatus.RETRYABLE_FAILURE,
                    DeliveryState.PENDING,
                    Duration.ofSeconds(45),
                    RetryScheduleSource.RETRY_AFTER
            ));
            assertThat(backoffSelected).isEqualTo(new CompletionDecision(
                    AttemptStatus.RETRYABLE_FAILURE,
                    DeliveryState.PENDING,
                    Duration.ofSeconds(300),
                    RetryScheduleSource.BACKOFF
            ));
            assertThat(exhausted.deliveryState()).isEqualTo(DeliveryState.EXHAUSTED);
            assertThat(exhausted.retryDelay()).isNull();
            assertThat(exhausted.retryScheduleSource()).isNull();
        }
    }

    @Test
    void retainsBackoffForNetworkFailuresAndHintsThatDoNotExceedIt() {
        try (AttemptCompletion networkFailure = AttemptCompletion.observed(DispatchObservation.failure(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                DispatchObservation.FailureCode.NETWORK_FAILURE,
                Duration.ofMillis(10)
        )); AttemptCompletion shortHint = AttemptCompletion.observed(DispatchObservation.httpResponse(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                503,
                Duration.ofMillis(10),
                new byte[0],
                false,
                Optional.of(Duration.ofSeconds(2))
        ))) {
            RetryDelayPolicy policy = new RetryDelayPolicy(() -> 0.0d);

            assertThat(policy.forObserved(networkFailure, 1).retryScheduleSource())
                    .isEqualTo(RetryScheduleSource.BACKOFF);
            assertThat(policy.forObserved(shortHint, 1)).isEqualTo(new CompletionDecision(
                    AttemptStatus.RETRYABLE_FAILURE,
                    DeliveryState.PENDING,
                    Duration.ofMillis(2500),
                    RetryScheduleSource.BACKOFF
            ));
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
                    .isEqualTo(new CompletionDecision(AttemptStatus.SUCCEEDED, DeliveryState.SUCCEEDED, null, null));
            assertThat(policy.forObserved(permanent, 1)).isEqualTo(
                    new CompletionDecision(AttemptStatus.PERMANENT_FAILURE, DeliveryState.FAILED_PERMANENT, null, null)
            );
        }
    }
}
