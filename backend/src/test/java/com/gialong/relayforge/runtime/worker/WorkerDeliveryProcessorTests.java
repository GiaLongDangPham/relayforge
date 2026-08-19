package com.gialong.relayforge.runtime.worker;

import com.gialong.relayforge.delivery.api.AttemptFinalizationResult;
import com.gialong.relayforge.delivery.api.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.DeliveryAttemptFinalizer;
import com.gialong.relayforge.delivery.api.DeliveryAttemptStarter;
import com.gialong.relayforge.delivery.api.DeliveryClaimer;
import com.gialong.relayforge.delivery.api.DispatchInstruction;
import com.gialong.relayforge.delivery.api.DispatchObservation;
import com.gialong.relayforge.delivery.api.OutboundWebhookDispatcher;
import com.gialong.relayforge.endpoint.api.EndpointAttemptSnapshot;
import com.gialong.relayforge.endpoint.api.EndpointSigningMaterial;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerDeliveryProcessorTests {

    @Test
    void retriesOnlyTheFinalizationWriteAfterOneObservedDispatch() {
        ClaimedDelivery claim = claim();
        WorkerClaimCoordinator coordinator = new WorkerClaimCoordinator(new OneClaimClaimer(claim), properties());
        WorkerClaimCoordinator.BoundClaim boundClaim = coordinator.claimAvailable().getFirst();
        RecordingDispatcher dispatcher = new RecordingDispatcher();
        FlakyFinalizer finalizer = new FlakyFinalizer();
        DeliveryAttemptStarter starter = (ignored, lease) -> Optional.of(instruction(claim));
        WorkerDeliveryProcessor processor = new WorkerDeliveryProcessor(starter, dispatcher, finalizer, properties());

        processor.process(boundClaim);

        assertThat(dispatcher.calls.get()).isEqualTo(1);
        assertThat(finalizer.finalizationCalls.get()).isEqualTo(2);
        assertThat(finalizer.leaseChecks.get()).isEqualTo(1);
        assertThat(coordinator.availablePermits()).isEqualTo(1);
    }

    private static WorkerProperties properties() {
        return new WorkerProperties(
                1,
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
                Instant.now().plusSeconds(20)
        );
    }

    private static DispatchInstruction instruction(ClaimedDelivery claim) {
        return new DispatchInstruction(
                claim.projectId(),
                UUID.randomUUID(),
                claim.deliveryId(),
                UUID.randomUUID(),
                claim.claimToken(),
                1,
                "invoice.paid",
                Instant.parse("2026-08-14T12:00:00Z"),
                Instant.parse("2026-08-14T12:00:01Z"),
                Instant.parse("2026-08-14T12:00:21Z"),
                "{}".getBytes(StandardCharsets.UTF_8),
                new EndpointAttemptSnapshot(
                        claim.projectId(),
                        claim.endpointId(),
                        "https://receiver.example/webhooks",
                        new NoopSigningMaterial()
                )
        );
    }

    private static final class OneClaimClaimer implements DeliveryClaimer {

        private final ClaimedDelivery claim;

        private OneClaimClaimer(ClaimedDelivery claim) {
            this.claim = claim;
        }

        @Override
        public List<ClaimedDelivery> claim(int requestedCapacity, Duration initialLease) {
            return List.of(claim);
        }

        @Override
        public int recoverExpiredPreAttemptClaims(int recoveryCapacity) {
            return 0;
        }
    }

    private static final class RecordingDispatcher implements OutboundWebhookDispatcher {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public DispatchObservation dispatch(DispatchInstruction instruction) {
            calls.incrementAndGet();
            return DispatchObservation.httpResponse(
                    DispatchObservation.Outcome.SUCCEEDED,
                    204,
                    Duration.ofMillis(10),
                    new byte[0],
                    false
            );
        }
    }

    private static final class FlakyFinalizer implements DeliveryAttemptFinalizer {

        private final AtomicInteger finalizationCalls = new AtomicInteger();
        private final AtomicInteger leaseChecks = new AtomicInteger();

        @Override
        public AttemptFinalizationResult finalizeAttempt(DispatchInstruction instruction, DispatchObservation observation) {
            if (finalizationCalls.incrementAndGet() == 1) {
                throw new IllegalStateException("database unavailable after HTTP");
            }
            return AttemptFinalizationResult.FINALIZED;
        }

        @Override
        public boolean hasCurrentLease(DispatchInstruction instruction, Duration minimumRemaining) {
            leaseChecks.incrementAndGet();
            return true;
        }
    }

    private static final class NoopSigningMaterial implements EndpointSigningMaterial {

        @Override
        public byte[] decryptForDispatch() {
            return new byte[32];
        }

        @Override
        public void close() {
        }
    }
}
