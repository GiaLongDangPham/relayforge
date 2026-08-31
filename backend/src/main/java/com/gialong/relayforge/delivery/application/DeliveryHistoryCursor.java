package com.gialong.relayforge.delivery.application;
import com.gialong.relayforge.delivery.api.history.DeliveryDisplayStatus;

import com.gialong.relayforge.delivery.api.history.DeliveryDisplayStatus;

import java.nio.ByteBuffer;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/** Fixed-size versioned delivery-list keyset position bound to its owner, query shape, filters, and sort direction. */
public record DeliveryHistoryCursor(
        QueryKind queryKind,
        UUID ownerId,
        UUID projectId,
        UUID eventId,
        UUID endpointId,
        DeliveryDisplayStatus displayStatus,
        Instant createdAt,
        UUID deliveryId
) {

    private static final byte FORMAT_VERSION = 1;
    private static final int ENCODED_SIZE = 1 + 1 + 16 + 16 + 1 + 16 + 1 + 16 + 1 + 1 + 8 + 4 + 16;

    public DeliveryHistoryCursor {
        Objects.requireNonNull(queryKind, "queryKind must not be null");
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(projectId, "projectId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        if (queryKind == QueryKind.EVENT_DELIVERIES_ASC && eventId == null) {
            throw new IllegalArgumentException("event delivery cursor requires eventId");
        }
    }

    static String encode(DeliveryHistoryCursor position) {
        ByteBuffer buffer = ByteBuffer.allocate(ENCODED_SIZE);
        buffer.put(FORMAT_VERSION);
        buffer.put(position.queryKind().wireValue());
        writeUuid(buffer, position.ownerId());
        writeUuid(buffer, position.projectId());
        writeNullableUuid(buffer, position.eventId());
        writeNullableUuid(buffer, position.endpointId());
        buffer.put(position.displayStatus() == null ? (byte) -1 : (byte) position.displayStatus().ordinal());
        buffer.putLong(position.createdAt().getEpochSecond());
        buffer.putInt(position.createdAt().getNano());
        writeUuid(buffer, position.deliveryId());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    static DeliveryHistoryCursor decodeForQuery(
            QueryKind expectedQueryKind,
            UUID expectedOwnerId,
            UUID expectedProjectId,
            UUID expectedEventId,
            UUID expectedEndpointId,
            DeliveryDisplayStatus expectedDisplayStatus,
            String cursor
    ) {
        if (cursor == null || cursor.isBlank()) {
            throw invalidCursor();
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
            QueryKind queryKind = QueryKind.fromWireValue(buffer.get());
            UUID ownerId = readUuid(buffer);
            UUID projectId = readUuid(buffer);
            UUID eventId = readNullableUuid(buffer);
            UUID endpointId = readNullableUuid(buffer);
            DeliveryDisplayStatus displayStatus = readNullableDisplayStatus(buffer.get());
            Instant createdAt = Instant.ofEpochSecond(buffer.getLong(), buffer.getInt());
            UUID deliveryId = readUuid(buffer);
            if (buffer.hasRemaining()
                    || queryKind != expectedQueryKind
                    || !ownerId.equals(expectedOwnerId)
                    || !projectId.equals(expectedProjectId)
                    || !Objects.equals(eventId, expectedEventId)
                    || !Objects.equals(endpointId, expectedEndpointId)
                    || displayStatus != expectedDisplayStatus) {
                throw invalidCursor();
            }
            return new DeliveryHistoryCursor(
                    queryKind,
                    ownerId,
                    projectId,
                    eventId,
                    endpointId,
                    displayStatus,
                    createdAt,
                    deliveryId
            );
        } catch (IllegalArgumentException | DateTimeException exception) {
            if ("invalid delivery history cursor".equals(exception.getMessage())) {
                throw exception;
            }
            throw invalidCursor();
        }
    }

    private static void writeNullableUuid(ByteBuffer buffer, UUID value) {
        buffer.put((byte) (value == null ? 0 : 1));
        if (value == null) {
            buffer.putLong(0L).putLong(0L);
            return;
        }
        writeUuid(buffer, value);
    }

    private static UUID readNullableUuid(ByteBuffer buffer) {
        return switch (buffer.get()) {
            case 0 -> {
                buffer.getLong();
                buffer.getLong();
                yield null;
            }
            case 1 -> readUuid(buffer);
            default -> throw invalidCursor();
        };
    }

    private static DeliveryDisplayStatus readNullableDisplayStatus(byte ordinal) {
        if (ordinal == -1) {
            return null;
        }
        DeliveryDisplayStatus[] values = DeliveryDisplayStatus.values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw invalidCursor();
        }
        return values[ordinal];
    }

    private static void writeUuid(ByteBuffer buffer, UUID value) {
        buffer.putLong(value.getMostSignificantBits());
        buffer.putLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(ByteBuffer buffer) {
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static IllegalArgumentException invalidCursor() {
        return new IllegalArgumentException("invalid delivery history cursor");
    }

    public enum QueryKind {
        EVENT_DELIVERIES_ASC((byte) 1),
        PROJECT_DELIVERIES_DESC((byte) 2);

        private final byte wireValue;

        QueryKind(byte wireValue) {
            this.wireValue = wireValue;
        }

        byte wireValue() {
            return wireValue;
        }

        static QueryKind fromWireValue(byte wireValue) {
            for (QueryKind value : values()) {
                if (value.wireValue == wireValue) {
                    return value;
                }
            }
            throw invalidCursor();
        }
    }
}
