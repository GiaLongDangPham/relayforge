package com.gialong.relayforge.runtime.worker;
import com.gialong.relayforge.delivery.api.processing.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.processing.DeliveryClaimer;

import com.gialong.relayforge.delivery.api.processing.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.processing.DeliveryClaimer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkerClaimCoordinatorTests {

    @Test
    void reservesBeforeClaimingAndKeepsOnlyBoundPermitsAfterAShortClaim() {
        RecordingClaimer claimer = new RecordingClaimer(List.of(claim(), claim()));
        WorkerClaimCoordinator coordinator = new WorkerClaimCoordinator(claimer, properties(3));

        List<WorkerClaimCoordinator.BoundClaim> claims = coordinator.claimAvailable();

        assertThat(claimer.requestedCapacities).containsExactly(3);
        assertThat(claims).hasSize(2);
        assertThat(coordinator.availablePermits()).isEqualTo(1);
        claims.forEach(WorkerClaimCoordinator.BoundClaim::close);
        assertThat(coordinator.availablePermits()).isEqualTo(3);
    }

    @Test
    void doesNotCallTheDatabaseClaimerWhenEveryPermitIsBound() {
        RecordingClaimer claimer = new RecordingClaimer(List.of(claim(), claim()));
        WorkerClaimCoordinator coordinator = new WorkerClaimCoordinator(claimer, properties(2));
        List<WorkerClaimCoordinator.BoundClaim> first = coordinator.claimAvailable();

        assertThat(coordinator.claimAvailable()).isEmpty();
        assertThat(claimer.requestedCapacities).containsExactly(2);

        first.forEach(WorkerClaimCoordinator.BoundClaim::close);
    }

    @Test
    void releasesEveryReservationWhenClaimTransactionFails() {
        DeliveryClaimer failing = new DeliveryClaimer() {
            @Override
            public List<ClaimedDelivery> claim(int requestedCapacity, Duration initialLease) {
                throw new IllegalStateException("database unavailable");
            }

            @Override
            public int recoverExpiredPreAttemptClaims(int recoveryCapacity) {
                return 0;
            }
        };
        WorkerClaimCoordinator coordinator = new WorkerClaimCoordinator(failing, properties(2));

        assertThatThrownBy(coordinator::claimAvailable).isInstanceOf(IllegalStateException.class)
                .hasMessage("database unavailable");
        assertThat(coordinator.availablePermits()).isEqualTo(2);
    }

    private static WorkerProperties properties(int capacity) {
        return new WorkerProperties(
                capacity,
                Duration.ofSeconds(15),
                Duration.ofSeconds(20),
                Duration.ofMillis(500),
                Duration.ofMillis(100),
                Duration.ofSeconds(5),
                Duration.ofSeconds(1)
        );
    }

    private static ClaimedDelivery claim() {
        return new ClaimedDelivery(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().plusSeconds(15)
        );
    }

    private static final class RecordingClaimer implements DeliveryClaimer {

        private final List<ClaimedDelivery> claims;
        private final List<Integer> requestedCapacities = new ArrayList<>();

        private RecordingClaimer(List<ClaimedDelivery> claims) {
            this.claims = claims;
        }

        @Override
        public List<ClaimedDelivery> claim(int requestedCapacity, Duration initialLease) {
            requestedCapacities.add(requestedCapacity);
            return claims;
        }

        @Override
        public int recoverExpiredPreAttemptClaims(int recoveryCapacity) {
            return 0;
        }
    }
}
