package com.gialong.relayforge.delivery.api;

import java.util.UUID;

/**
 * Accepts one publisher command and atomically records its initial routing snapshot.
 */
public interface EventPublisher {

    PublishEventResult publish(UUID projectId, String idempotencyKey, String eventType, String payloadJson);
}
