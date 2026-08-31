package com.gialong.relayforge.delivery.application;
import com.gialong.relayforge.delivery.api.processing.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.processing.DeliveryClaimer;

import com.gialong.relayforge.delivery.api.processing.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.processing.DeliveryClaimer;
import com.gialong.relayforge.endpoint.api.EndpointClaimEligibilityQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owns the short, database-only claim and pre-attempt lease-recovery transactions.
 */
@Service
final class DeliveryClaimService implements DeliveryClaimer {

    private final DeliveryStore deliveryStore;
    private final EndpointClaimEligibilityQuery endpointEligibility;
    private final TransactionTemplate transaction;

    DeliveryClaimService(
            DeliveryStore deliveryStore,
            EndpointClaimEligibilityQuery endpointEligibility,
            PlatformTransactionManager transactionManager
    ) {
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore must not be null");
        this.endpointEligibility = Objects.requireNonNull(endpointEligibility, "endpointEligibility must not be null");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public List<ClaimedDelivery> claim(int requestedCapacity, Duration initialLease) {
        requirePositiveCapacity(requestedCapacity, "requestedCapacity");
        Duration requiredLease = requireMillisecondLease(initialLease);
        return List.copyOf(Objects.requireNonNull(
                transaction.execute(status -> claimInTransaction(requestedCapacity, requiredLease)),
                "claim transaction returned no result"
        ));
    }

    @Override
    public int recoverExpiredPreAttemptClaims(int recoveryCapacity) {
        requirePositiveCapacity(recoveryCapacity, "recoveryCapacity");
        return Objects.requireNonNull(
                transaction.execute(status -> deliveryStore.recoverExpiredPreAttemptClaims(recoveryCapacity)),
                "recovery transaction returned no result"
        );
    }

    private List<ClaimedDelivery> claimInTransaction(int requestedCapacity, Duration initialLease) {
        List<UUID> enabledEndpointIds = endpointEligibility.findEnabledEndpointIdsForClaim();
        if (enabledEndpointIds.isEmpty()) {
            return List.of();
        }

        List<ClaimCandidate> candidates = deliveryStore.lockDuePendingForEnabledEndpoints(
                enabledEndpointIds,
                requestedCapacity
        );
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<UUID> stillEnabledEndpointIds = endpointEligibility.lockAndFindEnabledForClaim(
                candidates.stream().map(ClaimCandidate::endpointId).distinct().toList()
        );
        return candidates.stream()
                .filter(candidate -> stillEnabledEndpointIds.contains(candidate.endpointId()))
                .map(candidate -> deliveryStore.claim(
                        candidate,
                        UUID.randomUUID(),
                        initialLease
                ))
                .flatMap(Optional::stream)
                .toList();
    }

    private static void requirePositiveCapacity(int capacity, String name) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static Duration requireMillisecondLease(Duration lease) {
        Duration requiredLease = Objects.requireNonNull(lease, "initialLease must not be null");
        if (requiredLease.isNegative() || requiredLease.isZero() || requiredLease.toMillis() == 0) {
            throw new IllegalArgumentException("initialLease must be at least one millisecond");
        }
        return requiredLease;
    }
}
