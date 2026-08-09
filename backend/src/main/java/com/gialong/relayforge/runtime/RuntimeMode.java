package com.gialong.relayforge.runtime;

/**
 * The single process role selected for one RelayForge application instance.
 */
public enum RuntimeMode {
    API,
    WORKER;

    static RuntimeMode fromConfigurationValue(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "relayforge.runtime is required and must be either api or worker"
            );
        }

        return switch (value) {
            case "api" -> API;
            case "worker" -> WORKER;
            default -> throw new IllegalArgumentException(
                    "relayforge.runtime must be exactly api or worker, but was: " + value
            );
        };
    }
}
