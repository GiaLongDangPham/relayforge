package com.gialong.relayforge.delivery.api;

/** Worker-invoked use case for bounded removal of expired complete terminal history graphs. */
public interface TerminalHistoryRetention {

    RetentionCleanupResult cleanExpiredTerminalHistory(int retentionDays, int maxGraphs);
}
