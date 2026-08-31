package com.gialong.relayforge.runtime.publisher;

public final class PublisherRateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public PublisherRateLimitExceededException(long retryAfterSeconds) {
        super("publisher rate limit exceeded");
        if (retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("retryAfterSeconds must be positive");
        }
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
