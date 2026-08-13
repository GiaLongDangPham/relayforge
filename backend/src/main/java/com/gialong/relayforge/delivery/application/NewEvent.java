package com.gialong.relayforge.delivery.application;

import tools.jackson.databind.JsonNode;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public record NewEvent(
        UUID id,
        UUID projectId,
        String eventType,
        JsonNode payload,
        String idempotencyKey,
        short fingerprintVersion,
        byte[] commandFingerprint
) {

    public NewEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        payload = Objects.requireNonNull(payload, "payload must not be null").deepCopy();
        Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
        if (fingerprintVersion <= 0) {
            throw new IllegalArgumentException("fingerprintVersion must be positive");
        }
        commandFingerprint = Arrays.copyOf(
                Objects.requireNonNull(commandFingerprint, "commandFingerprint must not be null"),
                commandFingerprint.length
        );
    }

    @Override
    public byte[] commandFingerprint() {
        return Arrays.copyOf(commandFingerprint, commandFingerprint.length);
    }
}
