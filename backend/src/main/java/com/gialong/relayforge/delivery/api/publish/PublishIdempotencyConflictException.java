package com.gialong.relayforge.delivery.api.publish;

/**
 * The publisher reused an idempotency key for a different event command.
 */
public final class PublishIdempotencyConflictException extends RuntimeException {
}
