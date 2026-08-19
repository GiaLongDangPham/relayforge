package com.gialong.relayforge.delivery.api;

import java.util.List;
import java.util.Objects;

public record DeliveryHistoryPage(List<DeliveryHistorySummary> items, String nextCursor) {

    public DeliveryHistoryPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
