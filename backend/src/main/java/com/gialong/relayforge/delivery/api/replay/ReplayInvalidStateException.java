package com.gialong.relayforge.delivery.api.replay;

/** Raised when an owned source delivery is not currently exhausted. */
public final class ReplayInvalidStateException extends RuntimeException {

    public ReplayInvalidStateException() {
        super("only exhausted deliveries may be replayed");
    }
}
