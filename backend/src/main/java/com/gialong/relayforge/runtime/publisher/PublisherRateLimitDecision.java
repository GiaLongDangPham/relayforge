package com.gialong.relayforge.runtime.publisher;

/**
 * The result of one local publisher admission attempt.
 */
public record PublisherRateLimitDecision(boolean admitted, long retryAfterSeconds) {

    public PublisherRateLimitDecision {
        if (admitted && retryAfterSeconds != 0) {
            throw new IllegalArgumentException("an admitted decision must not have Retry-After");
        }
        if (!admitted && retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("a rejected decision requires positive Retry-After");
        }
    }

    static PublisherRateLimitDecision permit() {
        return new PublisherRateLimitDecision(true, 0);
    }

    static PublisherRateLimitDecision reject(long retryAfterSeconds) {
        return new PublisherRateLimitDecision(false, retryAfterSeconds);
    }
}
