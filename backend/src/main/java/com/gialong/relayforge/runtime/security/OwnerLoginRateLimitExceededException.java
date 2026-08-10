package com.gialong.relayforge.runtime.security;

public final class OwnerLoginRateLimitExceededException extends RuntimeException {

    public OwnerLoginRateLimitExceededException() {
        super("Too many owner login attempts");
    }
}
