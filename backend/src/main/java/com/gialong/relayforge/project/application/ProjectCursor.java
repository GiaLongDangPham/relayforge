package com.gialong.relayforge.project.application;

import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned keyset position bound to one owner-scoped project list.
 */
public record ProjectCursor(UUID ownerId, Instant createdAt, UUID projectId) {

    private static final byte FORMAT_VERSION = 1;
    private static final int ENCODED_SIZE = 45;

    public ProjectCursor {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
    }

    static String encode(ProjectCursor position) {
        ByteBuffer buffer = ByteBuffer.allocate(ENCODED_SIZE);
        buffer.put(FORMAT_VERSION);
        writeUuid(buffer, position.ownerId());
        buffer.putLong(position.createdAt().getEpochSecond());
        buffer.putInt(position.createdAt().getNano());
        writeUuid(buffer, position.projectId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    static ProjectCursor decodeForOwner(UUID expectedOwnerId, String encodedCursor) {
        if (encodedCursor == null || encodedCursor.isBlank()) {
            throw new IllegalArgumentException("cursor must not be blank");
        }

        try {
            byte[] encoded = Base64.getUrlDecoder().decode(encodedCursor);
            if (encoded.length != ENCODED_SIZE) {
                throw invalidCursor();
            }

            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            if (buffer.get() != FORMAT_VERSION) {
                throw invalidCursor();
            }
            UUID ownerId = readUuid(buffer);
            Instant createdAt = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            UUID projectId = readUuid(buffer);
            if (!ownerId.equals(expectedOwnerId)) {
                throw invalidCursor();
            }
            return new ProjectCursor(ownerId, createdAt, projectId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            if (exception.getMessage() != null && exception.getMessage().equals("invalid project cursor")) {
                throw exception;
            }
            throw invalidCursor();
        }
    }

    private static void writeUuid(ByteBuffer buffer, UUID id) {
        buffer.putLong(id.getMostSignificantBits());
        buffer.putLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static IllegalArgumentException invalidCursor() {
        return new IllegalArgumentException("invalid project cursor");
    }
}
