package com.gialong.relayforge.delivery.application;

import com.gialong.relayforge.delivery.api.ClaimedDelivery;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for immutable event acceptance, delivery work, and durable attempt-start state.
 */
public interface DeliveryStore {

    Optional<StoredEvent> insertEventIfAbsent(NewEvent event);

    Optional<StoredEvent> findEventByProjectAndIdempotencyKey(UUID projectId, String idempotencyKey);

    boolean eventHasEquivalentCommand(UUID eventId, String eventType, String payloadJson);

    void insertOriginalDeliveries(UUID projectId, UUID eventId, List<PendingDelivery> deliveries);

    int countOriginalDeliveries(UUID eventId);

    List<ClaimCandidate> lockDuePendingForEnabledEndpoints(Collection<UUID> enabledEndpointIds, int capacity);

    ClaimedDelivery claim(ClaimCandidate candidate, UUID claimToken, Duration initialLease);

    int recoverExpiredPreAttemptClaims(int capacity);

    Optional<AttemptStartCandidate> lockCurrentClaimForAttemptStart(ClaimedDelivery claim);

    boolean releaseClaimBeforeAttempt(AttemptStartCandidate candidate);

    Optional<StartedAttempt> startAttempt(
            AttemptStartCandidate candidate,
            UUID attemptId,
            short destinationFingerprintVersion,
            byte[] destinationFingerprint,
            Duration attemptExecutionLease
    );
}
