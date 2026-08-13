package com.gialong.relayforge.delivery.api;

/**
 * The publisher reused an idempotency key for a different event command.
 */
public final class PublishIdempotencyConflictException extends RuntimeException {
}
