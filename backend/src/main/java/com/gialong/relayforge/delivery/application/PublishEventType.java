package com.gialong.relayforge.delivery.application;

import java.util.Objects;

final class PublishEventType {

    private static final int MAX_LENGTH = 200;

    private PublishEventType() {
    }

    static String requireNormalized(String eventType) {
        String value = Objects.requireNonNull(eventType, "eventType must not be null");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("eventType must be trimmed, nonblank, and at most " + MAX_LENGTH + " characters");
        }
        return normalized;
    }
}
