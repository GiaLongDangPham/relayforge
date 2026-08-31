package com.gialong.relayforge.delivery.api.publish;

/** Immutable delivery-owned limit for newly accepted events in one UTC day. */
public record PublisherQuotaSettings(int dailyAcceptedEvents) {

    public static final int MAX_DAILY_ACCEPTED_EVENTS = 1_000_000;

    public PublisherQuotaSettings {
        if (dailyAcceptedEvents <= 0 || dailyAcceptedEvents > MAX_DAILY_ACCEPTED_EVENTS) {
            throw new IllegalArgumentException(
                    "dailyAcceptedEvents must be between 1 and " + MAX_DAILY_ACCEPTED_EVENTS
            );
        }
    }
}
