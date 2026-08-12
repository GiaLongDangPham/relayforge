package com.gialong.relayforge.endpoint.application;

import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Versioned list position bound to one owner and project endpoint collection.
 */
public record EndpointCursor(UUID ownerId, UUID projectId, Instant createdAt, UUID endpointId) {

    private static final byte FORMAT_VERSION = 1;
    private static final int ENCODED_SIZE = 61;

    public EndpointCursor {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(endpointId, "endpointId must not be null");
    }

    static String encode(EndpointCursor position) {
        ByteBuffer buffer = ByteBuffer.allocate(ENCODED_SIZE);
        buffer.put(FORMAT_VERSION);
        writeUuid(buffer, position.ownerId());
        writeUuid(buffer, position.projectId());
        buffer.putLong(position.createdAt().getEpochSecond());
        buffer.putInt(position.createdAt().getNano());
        writeUuid(buffer, position.endpointId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    static EndpointCursor decodeForOwnerAndProject(UUID expectedOwnerId, UUID expectedProjectId, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw new IllegalArgumentException("cursor must not be blank");
        }
        try {
            byte[] encoded = Base64.getUrlDecoder().decode(cursor);
            if (encoded.length != ENCODED_SIZE) {
                throw invalidCursor();
            }
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            if (buffer.get() != FORMAT_VERSION) {
                throw invalidCursor();
            }
            UUID ownerId = readUuid(buffer);
            UUID projectId = readUuid(buffer);
            Instant createdAt = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            UUID endpointId = readUuid(buffer);
            if (!ownerId.equals(expectedOwnerId) || !projectId.equals(expectedProjectId)) {
                throw invalidCursor();
            }
            return new EndpointCursor(ownerId, projectId, createdAt, endpointId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            if ("invalid endpoint cursor".equals(exception.getMessage())) {
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
        return new IllegalArgumentException("invalid endpoint cursor");
    }
}
