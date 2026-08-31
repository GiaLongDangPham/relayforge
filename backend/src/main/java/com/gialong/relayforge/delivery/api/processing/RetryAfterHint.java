package com.gialong.relayforge.delivery.api.processing;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Parses RelayForge's deliberately narrow receiver retry hint without retaining
 * the receiver-controlled raw header value.
 */
public final class RetryAfterHint {

    public static final Duration MAX_DELAY = Duration.ofSeconds(300);

    private RetryAfterHint() {
    }

    public static Optional<Duration> parse(int httpStatus, List<String> headerValues) {
        Objects.requireNonNull(headerValues, "headerValues must not be null");
        if (httpStatus != 429 && httpStatus != 503 || headerValues.size() != 1) {
            return Optional.empty();
        }
        String value = headerValues.getFirst();
        if (value == null) {
            return Optional.empty();
        }

        String trimmed = trimOptionalWhitespace(value);
        if (trimmed.isEmpty()) {
            return Optional.empty();
        }

        long seconds = 0;
        boolean capped = false;
        for (int index = 0; index < trimmed.length(); index++) {
            char character = trimmed.charAt(index);
            if (character < '0' || character > '9') {
                return Optional.empty();
            }
            int digit = character - '0';
            if (!capped) {
                if (seconds > MAX_DELAY.toSeconds() / 10
                        || seconds == MAX_DELAY.toSeconds() / 10 && digit > MAX_DELAY.toSeconds() % 10) {
                    capped = true;
                } else {
                    seconds = seconds * 10 + digit;
                }
            }
        }
        return Optional.of(capped ? MAX_DELAY : Duration.ofSeconds(seconds));
    }

    private static String trimOptionalWhitespace(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isOptionalWhitespace(value.charAt(start))) {
            start++;
        }
        while (end > start && isOptionalWhitespace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isOptionalWhitespace(char value) {
        return value == ' ' || value == '\t';
    }
}
