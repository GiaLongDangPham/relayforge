package com.gialong.relayforge.runtime.deliveries;

final class DeliveryHistoryNotFoundException extends RuntimeException {

    DeliveryHistoryNotFoundException() {
        super("owner-scoped history resource was not found");
    }
}
