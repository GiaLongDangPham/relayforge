package com.gialong.relayforge.delivery.api.publish;

/** Raised when a new event would exceed its project's durable UTC-day quota. */
public final class PublishQuotaExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public PublishQuotaExceededException(long retryAfterSeconds) {
        super("publisher event quota is exhausted");
        if (retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
