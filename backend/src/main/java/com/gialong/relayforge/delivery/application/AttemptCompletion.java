package com.gialong.relayforge.delivery.application;

import com.gialong.relayforge.delivery.api.DispatchObservation;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * One bounded persistence-ready observation. Its response preview is cleared after the transaction attempt ends.
 */
public final class AttemptCompletion implements AutoCloseable {

    private final AttemptStatus status;
    private final Integer httpStatus;
    private final String failureCode;
    private final int latencyMilliseconds;
    private final boolean responseTruncated;
    private byte[] responsePreview;
    private boolean closed;

    private AttemptCompletion(
            AttemptStatus status,
            Integer httpStatus,
            String failureCode,
            int latencyMilliseconds,
            byte[] responsePreview,
            boolean responseTruncated
    ) {
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.httpStatus = httpStatus;
        this.failureCode = failureCode;
        if (latencyMilliseconds < 0) {
            throw new IllegalArgumentException("latencyMilliseconds must not be negative");
        }
        this.latencyMilliseconds = latencyMilliseconds;
        this.responsePreview = Arrays.copyOf(
                Objects.requireNonNull(responsePreview, "responsePreview must not be null"),
                responsePreview.length
        );
        this.responseTruncated = responseTruncated;
    }

    static AttemptCompletion observed(DispatchObservation observation) {
        DispatchObservation requiredObservation = Objects.requireNonNull(observation, "observation must not be null");
        AttemptStatus status = switch (requiredObservation.outcome()) {
            case SUCCEEDED -> AttemptStatus.SUCCEEDED;
            case RETRYABLE_FAILURE -> AttemptStatus.RETRYABLE_FAILURE;
            case PERMANENT_FAILURE -> AttemptStatus.PERMANENT_FAILURE;
        };
        OptionalInt observedHttpStatus = requiredObservation.httpStatus();
        Optional<DispatchObservation.FailureCode> observedFailureCode = requiredObservation.failureCode();
        return new AttemptCompletion(
                status,
                observedHttpStatus.isPresent() ? observedHttpStatus.getAsInt() : null,
                observedFailureCode.map(Enum::name).orElse(null),
                Math.toIntExact(requiredObservation.duration().toMillis()),
                requiredObservation.responsePreview(),
                requiredObservation.responseTruncated()
        );
    }

    public AttemptStatus status() {
        return status;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String failureCode() {
        return failureCode;
    }

    public int latencyMilliseconds() {
        return latencyMilliseconds;
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

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("attempt completion is closed");
        }
    }
}
