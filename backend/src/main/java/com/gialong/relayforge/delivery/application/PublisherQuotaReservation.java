package com.gialong.relayforge.delivery.application;

public record PublisherQuotaReservation(boolean admitted, long retryAfterSeconds) {

    public PublisherQuotaReservation {
        if (admitted && retryAfterSeconds != 0) {
            throw new IllegalArgumentException("an admitted quota reservation must not include retry-after");
        }
        if (!admitted && retryAfterSeconds <= 0) {
            throw new IllegalArgumentException("a rejected quota reservation needs positive retry-after");
        }
    }

    public static PublisherQuotaReservation admit() {
        return new PublisherQuotaReservation(true, 0);
    }

    public static PublisherQuotaReservation reject(long retryAfterSeconds) {
        return new PublisherQuotaReservation(false, retryAfterSeconds);
    }
}
