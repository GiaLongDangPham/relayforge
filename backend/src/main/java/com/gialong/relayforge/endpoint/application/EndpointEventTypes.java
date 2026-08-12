package com.gialong.relayforge.endpoint.application;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

final class EndpointEventTypes {

    private static final int MAX_LENGTH = 200;

    private EndpointEventTypes() {
    }

    static List<String> requireNormalized(Collection<String> eventTypes) {
        Collection<String> requiredEventTypes = Objects.requireNonNull(eventTypes, "eventTypes must not be null");
        if (requiredEventTypes.isEmpty()) {
            throw new IllegalArgumentException("eventTypes must not be empty");
        }

        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String eventType : requiredEventTypes) {
            String normalized = Objects.requireNonNull(eventType, "eventType must not be null").strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("eventType must not be blank");
            }
            if (normalized.length() > MAX_LENGTH) {
                throw new IllegalArgumentException("eventType must not exceed " + MAX_LENGTH + " characters");
            }
            if (!distinct.add(normalized)) {
                throw new IllegalArgumentException("eventTypes must not contain duplicates");
            }
        }
        return distinct.stream().sorted(Comparator.naturalOrder()).toList();
    }
}
