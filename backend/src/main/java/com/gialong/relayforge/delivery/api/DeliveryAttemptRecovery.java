package com.gialong.relayforge.delivery.api;

/**
 * Worker-facing recovery boundary for attempts whose current PostgreSQL-time lease has expired.
 */
public interface DeliveryAttemptRecovery {

    int recoverExpiredStartedAttempts(int recoveryCapacity);
}
