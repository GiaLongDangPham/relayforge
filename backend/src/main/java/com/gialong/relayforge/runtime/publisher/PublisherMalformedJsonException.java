package com.gialong.relayforge.runtime.publisher;

/**
 * Separates syntactically invalid JSON from a well-formed command that fails validation.
 */
final class PublisherMalformedJsonException extends RuntimeException {

    PublisherMalformedJsonException(Throwable cause) {
        super("publish request must be valid JSON", cause);
    }
}
