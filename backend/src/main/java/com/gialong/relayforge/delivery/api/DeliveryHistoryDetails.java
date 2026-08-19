package com.gialong.relayforge.delivery.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Detail connects one delivery to safe current endpoint metadata and immutable replay/attempt history. */
public record DeliveryHistoryDetails(
        DeliveryHistorySummary delivery,
        String eventType,
        DeliveryEndpointMetadata endpoint,
        List<UUID> replayDeliveryIds,
        AttemptHistorySummary latestAttempt
) {

    public DeliveryHistoryDetails {
        Objects.requireNonNull(delivery, "delivery must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        replayDeliveryIds = List.copyOf(Objects.requireNonNull(replayDeliveryIds, "replayDeliveryIds must not be null"));
    }
}
