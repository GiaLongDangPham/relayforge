package com.gialong.relayforge.delivery.api;

import java.util.List;
import java.util.Objects;

public record EventHistoryPage(List<EventHistorySummary> items, String nextCursor) {

    public EventHistoryPage {
        items = List.copyOf(Objects.requireNonNull(items, "items must not be null"));
    }
}
