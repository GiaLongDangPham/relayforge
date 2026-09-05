package com.gialong.relayforge.delivery.api.history;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Owner-scoped, secret-free inspection of immutable event and delivery history.
 */
public interface DeliveryHistory {

    Optional<DeliveryProjectHealth> findProjectHealth(UUID ownerId, UUID projectId);

    Optional<EventHistoryPage> listEvents(UUID ownerId, UUID projectId, String eventType, int limit, String cursor);

    Optional<EventHistoryDetails> findEvent(UUID ownerId, UUID projectId, UUID eventId);

    Optional<DeliveryHistoryPage> listEventDeliveries(UUID ownerId, UUID projectId, UUID eventId, int limit, String cursor);

    Optional<DeliveryHistoryPage> listDeliveries(
            UUID ownerId,
            UUID projectId,
            UUID eventId,
            UUID endpointId,
            DeliveryDisplayStatus displayStatus,
            int limit,
            String cursor
    );

    Optional<DeliveryHistoryDetails> findDelivery(UUID ownerId, UUID projectId, UUID deliveryId);

    Optional<List<AttemptHistorySummary>> listAttempts(UUID ownerId, UUID projectId, UUID deliveryId);

    Optional<AttemptHistoryDetails> findAttempt(UUID ownerId, UUID projectId, UUID deliveryId, UUID attemptId);
}
