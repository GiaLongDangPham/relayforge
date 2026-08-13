package com.gialong.relayforge.delivery.application;

import java.util.Objects;

final class PublishIdempotencyKey {

    private static final int MAX_LENGTH = 200;

    private PublishIdempotencyKey() {
    }

    static String requireValid(String idempotencyKey) {
        String value = Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (value.isBlank() || value.length() > MAX_LENGTH || !value.equals(value.strip())) {
            throw new IllegalArgumentException(
                    "idempotencyKey must be trimmed, nonblank, and at most " + MAX_LENGTH + " characters"
            );
        }
        return value;
    }
}
