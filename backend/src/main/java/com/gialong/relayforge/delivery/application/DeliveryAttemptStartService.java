package com.gialong.relayforge.delivery.application;

import com.gialong.relayforge.delivery.api.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.DeliveryAttemptStarter;
import com.gialong.relayforge.delivery.api.DispatchInstruction;
import com.gialong.relayforge.endpoint.api.EndpointAttemptSnapshot;
import com.gialong.relayforge.endpoint.api.EndpointAttemptSnapshotQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the short durable attempt-start transaction and never performs outbound network I/O.
 */
@Service
final class DeliveryAttemptStartService implements DeliveryAttemptStarter {

    private final DeliveryStore deliveryStore;
    private final EndpointAttemptSnapshotQuery endpointAttemptSnapshots;
    private final TransactionTemplate transaction;

    DeliveryAttemptStartService(
            DeliveryStore deliveryStore,
            EndpointAttemptSnapshotQuery endpointAttemptSnapshots,
            PlatformTransactionManager transactionManager
    ) {
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore must not be null");
        this.endpointAttemptSnapshots = Objects.requireNonNull(
                endpointAttemptSnapshots,
                "endpointAttemptSnapshots must not be null"
        );
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Optional<DispatchInstruction> start(ClaimedDelivery claim, Duration attemptExecutionLease) {
        ClaimedDelivery requiredClaim = Objects.requireNonNull(claim, "claim must not be null");
        Duration requiredLease = requireMillisecondLease(attemptExecutionLease);
        return Objects.requireNonNull(
                transaction.execute(status -> startInTransaction(requiredClaim, requiredLease)),
                "attempt-start transaction returned no result"
        );
    }

    private Optional<DispatchInstruction> startInTransaction(ClaimedDelivery claim, Duration attemptExecutionLease) {
        Optional<AttemptStartCandidate> candidate = deliveryStore.lockCurrentClaimForAttemptStart(claim);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        AttemptStartCandidate lockedCandidate = candidate.orElseThrow();
        Optional<EndpointAttemptSnapshot> endpointSnapshot = endpointAttemptSnapshots.lockAndFindEnabledAttemptSnapshot(
                lockedCandidate.projectId(),
                lockedCandidate.endpointId()
        );
        if (endpointSnapshot.isEmpty()) {
            if (!deliveryStore.releaseClaimBeforeAttempt(lockedCandidate)) {
                throw new IllegalStateException("locked current claim could not be released for a disabled endpoint");
            }
            return Optional.empty();
        }

        EndpointAttemptSnapshot snapshot = endpointSnapshot.orElseThrow();
        boolean snapshotTransferred = false;
        byte[] fingerprint = DestinationFingerprint.forExactDestinationUrl(snapshot.destinationUrl());
        try {
            StartedAttempt startedAttempt = deliveryStore.startAttempt(
                    lockedCandidate,
                    UUID.randomUUID(),
                    DestinationFingerprint.VERSION,
                    fingerprint,
                    attemptExecutionLease
            ).orElse(null);
            if (startedAttempt == null) {
                return Optional.empty();
            }
            DispatchInstruction instruction = new DispatchInstruction(
                    lockedCandidate.projectId(),
                    lockedCandidate.eventId(),
                    lockedCandidate.deliveryId(),
                    startedAttempt.attemptId(),
                    lockedCandidate.claimToken(),
                    startedAttempt.attemptNumber(),
                    lockedCandidate.eventType(),
                    lockedCandidate.acceptedAt(),
                    startedAttempt.startedAt(),
                    startedAttempt.leaseExpiresAt(),
                    lockedCandidate.payloadJson(),
                    snapshot
            );
            snapshotTransferred = true;
            return Optional.of(instruction);
        } finally {
            Arrays.fill(fingerprint, (byte) 0);
            if (!snapshotTransferred) {
                snapshot.close();
            }
        }
    }

    private static Duration requireMillisecondLease(Duration lease) {
        Duration requiredLease = Objects.requireNonNull(lease, "attemptExecutionLease must not be null");
        if (requiredLease.isNegative() || requiredLease.isZero() || requiredLease.toMillis() == 0) {
            throw new IllegalArgumentException("attemptExecutionLease must be at least one millisecond");
        }
        return requiredLease;
    }
}
