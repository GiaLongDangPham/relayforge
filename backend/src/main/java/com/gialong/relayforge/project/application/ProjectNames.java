package com.gialong.relayforge.project.application;

final class ProjectNames {

    private static final int MAX_NAME_LENGTH = 120;

    private ProjectNames() {
    }

    static String requireNormalized(String name) {
        if (name == null) {
            throw new IllegalArgumentException("project name must not be null");
        }

        String normalized = name.strip();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException("project name must be nonblank and at most 120 characters");
        }
        return normalized;
    }
}
