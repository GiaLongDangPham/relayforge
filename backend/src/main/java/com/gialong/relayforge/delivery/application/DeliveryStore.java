package com.gialong.relayforge.delivery.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for immutable event acceptance and original delivery work only.
 */
public interface DeliveryStore {

    Optional<StoredEvent> insertEventIfAbsent(NewEvent event);

    Optional<StoredEvent> findEventByProjectAndIdempotencyKey(UUID projectId, String idempotencyKey);

    boolean eventHasEquivalentCommand(UUID eventId, String eventType, String payloadJson);

    void insertOriginalDeliveries(UUID projectId, UUID eventId, List<PendingDelivery> deliveries);

    int countOriginalDeliveries(UUID eventId);
}
