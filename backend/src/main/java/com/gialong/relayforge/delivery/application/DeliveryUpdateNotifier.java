package com.gialong.relayforge.delivery.application;

import java.util.UUID;

/**
 * Technical, non-durable notification emitted from an already-successful delivery state transaction.
 * PostgreSQL decides whether receivers observe it only when that transaction commits.
 */
public interface DeliveryUpdateNotifier {

    void publishCommittedDeliveryChange(UUID projectId, UUID deliveryId);
}
