package com.gialong.relayforge.delivery.api;

/** Public delivery-owned query for bounded operational queue aggregates. */
public interface DeliveryOperationalSnapshotQuery {

    DeliveryOperationalSnapshot currentSnapshot();
}
