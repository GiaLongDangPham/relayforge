package com.gialong.relayforge.delivery.api.replay;

/** Raised only when one project replay key has already been associated with another source delivery. */
public final class ReplayIdempotencyConflictException extends RuntimeException {

    public ReplayIdempotencyConflictException() {
        super("replay idempotency key is already associated with a different source delivery");
    }
}
