package com.gialong.relayforge.endpoint.application;

final class RetryPolicyDelays {

    private RetryPolicyDelays() {
    }

    static Integer requireNullable(Integer minimumRetryDelaySeconds) {
        if (minimumRetryDelaySeconds == null) {
            return null;
        }
        if (minimumRetryDelaySeconds < 5 || minimumRetryDelaySeconds > 300) {
            throw new IllegalArgumentException("minimumRetryDelaySeconds must be between 5 and 300 when present");
        }
        return minimumRetryDelaySeconds;
    }
}
