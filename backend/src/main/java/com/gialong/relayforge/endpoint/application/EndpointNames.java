package com.gialong.relayforge.endpoint.application;

import java.util.Objects;

final class EndpointNames {

    private static final int MAX_LENGTH = 120;

    private EndpointNames() {
    }

    static String requireNormalized(String name) {
        String value = Objects.requireNonNull(name, "name must not be null").strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("name must not exceed " + MAX_LENGTH + " characters");
        }
        return value;
    }
}
