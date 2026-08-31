package com.gialong.relayforge.delivery.api.operations;

/** Public delivery-owned query for bounded operational queue aggregates. */
public interface DeliveryOperationalSnapshotQuery {

    DeliveryOperationalSnapshot currentSnapshot();
}
