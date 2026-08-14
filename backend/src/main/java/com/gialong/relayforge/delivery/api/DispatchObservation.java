package com.gialong.relayforge.delivery.api;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Bounded, in-memory-only evidence from one completed or prevented HTTP dispatch cycle.
 */
public final class DispatchObservation implements AutoCloseable {

    public enum Outcome {
        SUCCEEDED,
        RETRYABLE_FAILURE,
        PERMANENT_FAILURE
    }

    public enum FailureCode {
        DESTINATION_REJECTED,
        DESTINATION_RESOLUTION_FAILED,
        DISPATCH_TIMEOUT,
        SIGNING_FAILURE,
        NETWORK_FAILURE
    }

    private final Outcome outcome;
    private final Integer httpStatus;
    private final FailureCode failureCode;
    private final Duration duration;
    private final boolean responseTruncated;
    private byte[] responsePreview;
    private boolean closed;

    private DispatchObservation(
            Outcome outcome,
            Integer httpStatus,
            FailureCode failureCode,
            Duration duration,
            byte[] responsePreview,
            boolean responseTruncated
    ) {
        this.outcome = Objects.requireNonNull(outcome, "outcome must not be null");
        this.httpStatus = httpStatus;
        this.failureCode = failureCode;
        this.duration = requireNonNegative(duration);
        this.responsePreview = Arrays.copyOf(
                Objects.requireNonNull(responsePreview, "responsePreview must not be null"),
                responsePreview.length
        );
        this.responseTruncated = responseTruncated;
    }

    public static DispatchObservation httpResponse(
            Outcome outcome,
            int httpStatus,
            Duration duration,
            byte[] responsePreview,
            boolean responseTruncated
    ) {
        if (httpStatus < 100 || httpStatus > 599) {
            throw new IllegalArgumentException("httpStatus must be between 100 and 599");
        }
        boolean successfulStatus = httpStatus >= 200 && httpStatus < 300;
        boolean retryableStatus = httpStatus == 408 || httpStatus == 429 || (httpStatus >= 500 && httpStatus <= 599);
        if (outcome == Outcome.SUCCEEDED && !successfulStatus) {
            throw new IllegalArgumentException("only 2xx responses are successful");
        }
        if (outcome == Outcome.RETRYABLE_FAILURE && httpStatus != 408 && httpStatus != 429
                && (httpStatus < 500 || httpStatus > 599)) {
            throw new IllegalArgumentException("only 408, 429, and 5xx responses are retryable");
        }
        if (outcome == Outcome.PERMANENT_FAILURE && (successfulStatus || retryableStatus)) {
            throw new IllegalArgumentException("successful or retryable responses cannot be permanent failures");
        }
        return new DispatchObservation(outcome, httpStatus, null, duration, responsePreview, responseTruncated);
    }

    public static DispatchObservation failure(Outcome outcome, FailureCode failureCode, Duration duration) {
        if (outcome == Outcome.SUCCEEDED) {
            throw new IllegalArgumentException("a failure code cannot have a successful outcome");
        }
        if ((failureCode == FailureCode.DESTINATION_REJECTED || failureCode == FailureCode.SIGNING_FAILURE)
                && outcome != Outcome.PERMANENT_FAILURE) {
            throw new IllegalArgumentException("rejected destinations and signing failures are permanent");
        }
        if ((failureCode == FailureCode.DESTINATION_RESOLUTION_FAILED
                || failureCode == FailureCode.DISPATCH_TIMEOUT
                || failureCode == FailureCode.NETWORK_FAILURE)
                && outcome != Outcome.RETRYABLE_FAILURE) {
            throw new IllegalArgumentException("resolution, timeout, and network failures are retryable");
        }
        return new DispatchObservation(
                outcome,
                null,
                Objects.requireNonNull(failureCode, "failureCode must not be null"),
                duration,
                new byte[0],
                false
        );
    }

    public Outcome outcome() {
        return outcome;
    }

    public OptionalInt httpStatus() {
        return httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus);
    }

    public Optional<FailureCode> failureCode() {
        return Optional.ofNullable(failureCode);
    }

    public Duration duration() {
        return duration;
    }

    public boolean responseTruncated() {
        return responseTruncated;
    }

    public synchronized byte[] responsePreview() {
        ensureOpen();
        return Arrays.copyOf(responsePreview, responsePreview.length);
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            Arrays.fill(responsePreview, (byte) 0);
            responsePreview = new byte[0];
            closed = true;
        }
    }

    @Override
    public String toString() {
        return "DispatchObservation[outcome=" + outcome + ", httpStatus=" + httpStatus + ", failureCode="
                + failureCode + ", duration=" + duration + ", responsePreview=<redacted>]";
    }

    private static Duration requireNonNegative(Duration value) {
        Duration required = Objects.requireNonNull(value, "duration must not be null");
        if (required.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        return required;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("dispatch observation is closed");
        }
    }
}
