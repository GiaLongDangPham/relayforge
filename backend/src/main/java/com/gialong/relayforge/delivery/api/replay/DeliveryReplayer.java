package com.gialong.relayforge.delivery.api.replay;

import java.util.Optional;
import java.util.UUID;

/** Creates at most one linked fresh delivery for an owner-requested exhausted source delivery. */
public interface DeliveryReplayer {

    Optional<ReplayDeliveryResult> replay(UUID ownerId, UUID projectId, UUID sourceDeliveryId, String idempotencyKey);
}
