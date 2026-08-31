package com.gialong.relayforge.delivery.api.history;

import java.util.Objects;

/**
 * Event detail retains the raw JSON representation only for API adaptation. Its diagnostic form redacts payload.
 */
public record EventHistoryDetails(EventHistorySummary event, String payloadJson, EventDeliverySummary deliverySummary) {

    public EventHistoryDetails {
        Objects.requireNonNull(event, "event must not be null");
        Objects.requireNonNull(payloadJson, "payloadJson must not be null");
        Objects.requireNonNull(deliverySummary, "deliverySummary must not be null");
    }

    @Override
    public String toString() {
        return "EventHistoryDetails[event=" + event + ", payloadJson=<redacted>, deliverySummary=" + deliverySummary + "]";
    }
}
