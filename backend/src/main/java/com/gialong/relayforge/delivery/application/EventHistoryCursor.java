package com.gialong.relayforge.delivery.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Versioned event-list keyset position bound to one owner, project, and exact event-type filter. */
public record EventHistoryCursor(UUID ownerId, UUID projectId, String eventType, Instant acceptedAt, UUID eventId) {

    private static final byte FORMAT_VERSION = 1;

    public EventHistoryCursor {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(acceptedAt, "acceptedAt must not be null");
        Objects.requireNonNull(eventId, "eventId must not be null");
    }

    static String encode(EventHistoryCursor position) {
        byte[] eventType = position.eventType() == null
                ? new byte[0]
                : position.eventType().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(1 + 16 + 16 + 1 + 2 + eventType.length + 8 + 4 + 16);
        buffer.put(FORMAT_VERSION);
        writeUuid(buffer, position.ownerId());
        writeUuid(buffer, position.projectId());
        buffer.put((byte) (position.eventType() == null ? 0 : 1));
        buffer.putShort((short) eventType.length);
        buffer.put(eventType);
        buffer.putLong(position.acceptedAt().getEpochSecond());
        buffer.putInt(position.acceptedAt().getNano());
        writeUuid(buffer, position.eventId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    static EventHistoryCursor decodeForQuery(UUID ownerId, UUID projectId, String eventType, String cursor) {
        if (cursor == null || cursor.isBlank()) {
            throw invalidCursor();
        }
        try {
            byte[] encoded = Base64.getUrlDecoder().decode(cursor);
            ByteBuffer buffer = ByteBuffer.wrap(encoded);
            if (buffer.remaining() < 1 + 16 + 16 + 1 + 2 + 8 + 4 + 16 || buffer.get() != FORMAT_VERSION) {
                throw invalidCursor();
            }
            UUID cursorOwnerId = readUuid(buffer);
            UUID cursorProjectId = readUuid(buffer);
            boolean hasEventType = switch (buffer.get()) {
                case 0 -> false;
                case 1 -> true;
                default -> throw invalidCursor();
            };
            int eventTypeLength = Short.toUnsignedInt(buffer.getShort());
            if (eventTypeLength > buffer.remaining() - 28) {
                throw invalidCursor();
            }
            String cursorEventType = new String(readBytes(buffer, eventTypeLength), StandardCharsets.UTF_8);
            if (!hasEventType && eventTypeLength != 0) {
                throw invalidCursor();
            }
            Instant acceptedAt = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            UUID eventId = readUuid(buffer);
            if (buffer.hasRemaining()
                    || !cursorOwnerId.equals(ownerId)
                    || !cursorProjectId.equals(projectId)
                    || !Objects.equals(hasEventType ? cursorEventType : null, eventType)) {
                throw invalidCursor();
            }
            return new EventHistoryCursor(cursorOwnerId, cursorProjectId, hasEventType ? cursorEventType : null, acceptedAt, eventId);
        } catch (IllegalArgumentException | DateTimeException exception) {
            if ("invalid event history cursor".equals(exception.getMessage())) {
                throw exception;
            }
            throw invalidCursor();
        }
    }

    private static byte[] readBytes(ByteBuffer buffer, int size) {
        byte[] value = new byte[size];
        buffer.get(value);
        return value;
    }

    private static void writeUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static IllegalArgumentException invalidCursor() {
        return new IllegalArgumentException("invalid event history cursor");
    }
}
