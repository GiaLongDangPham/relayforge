package com.gialong.relayforge.delivery.api;

/** Aggregate counts for one event; no current endpoint configuration is exposed. */
public record EventDeliverySummary(
        int totalCount,
        int activeCount,
        int succeededCount,
        int failedPermanentCount,
        int exhaustedCount
) {

    public EventDeliverySummary {
        if (totalCount < 0 || activeCount < 0 || succeededCount < 0 || failedPermanentCount < 0 || exhaustedCount < 0) {
            throw new IllegalArgumentException("delivery summary counts must not be negative");
        }
    }
}
