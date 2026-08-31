package com.gialong.relayforge.delivery.application;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Persisted equal-jitter retry scheduling for attempts one through four.
 */
@Component
final class RetryDelayPolicy {

    private static final Duration[] BASE_DELAYS = {
            Duration.ofSeconds(5),
            Duration.ofSeconds(20),
            Duration.ofSeconds(80),
            Duration.ofSeconds(300)
    };

    private final DoubleSupplier random;

    RetryDelayPolicy() {
        this(() -> ThreadLocalRandom.current().nextDouble());
    }

    RetryDelayPolicy(DoubleSupplier random) {
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    CompletionDecision forObserved(AttemptCompletion completion, int attemptNumber) {
        Objects.requireNonNull(completion, "completion must not be null");
        return switch (completion.status()) {
            case SUCCEEDED -> new CompletionDecision(AttemptStatus.SUCCEEDED, DeliveryState.SUCCEEDED, null, null);
            case PERMANENT_FAILURE -> new CompletionDecision(
                    AttemptStatus.PERMANENT_FAILURE,
                    DeliveryState.FAILED_PERMANENT,
                    null,
                    null
            );
            case RETRYABLE_FAILURE -> retryable(
                    AttemptStatus.RETRYABLE_FAILURE,
                    attemptNumber,
                    completion.retryAfterDelay().orElse(null)
            );
            case UNKNOWN -> throw new IllegalArgumentException("UNKNOWN is reserved for lease recovery");
        };
    }

    CompletionDecision forUnknownRecovery(int attemptNumber) {
        return retryable(AttemptStatus.UNKNOWN, attemptNumber, null);
    }

    private CompletionDecision retryable(AttemptStatus status, int attemptNumber, Duration retryAfterDelay) {
        if (attemptNumber < 1 || attemptNumber > 5) {
            throw new IllegalArgumentException("attemptNumber must be between one and five");
        }
        if (attemptNumber == 5) {
            return new CompletionDecision(status, DeliveryState.EXHAUSTED, null, null);
        }
        Duration backoff = equalJitter(BASE_DELAYS[attemptNumber - 1]);
        if (retryAfterDelay != null && retryAfterDelay.compareTo(backoff) > 0) {
            return new CompletionDecision(status, DeliveryState.PENDING, retryAfterDelay, RetryScheduleSource.RETRY_AFTER);
        }
        return new CompletionDecision(status, DeliveryState.PENDING, backoff, RetryScheduleSource.BACKOFF);
    }

    private Duration equalJitter(Duration baseDelay) {
        double draw = random.getAsDouble();
        if (draw < 0.0d || draw > 1.0d || Double.isNaN(draw)) {
            throw new IllegalStateException("retry jitter source must return a value between zero and one");
        }
        long baseMilliseconds = baseDelay.toMillis();
        long halfMilliseconds = baseMilliseconds / 2;
        long jitterMilliseconds = (long) Math.floor(draw * halfMilliseconds);
        if (draw == 1.0d) {
            jitterMilliseconds = halfMilliseconds;
        }
        return Duration.ofMillis(halfMilliseconds + jitterMilliseconds);
    }
}
