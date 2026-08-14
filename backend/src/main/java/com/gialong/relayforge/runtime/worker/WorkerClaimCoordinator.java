package com.gialong.relayforge.runtime.worker;

import com.gialong.relayforge.delivery.api.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.DeliveryClaimer;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reserves local capacity before claiming. Group 7 will attach bound claims to attempt-start tasks.
 */
public final class WorkerClaimCoordinator {

    private final DeliveryClaimer deliveryClaimer;
    private final WorkerProperties properties;
    private final Semaphore permits;

    public WorkerClaimCoordinator(DeliveryClaimer deliveryClaimer, WorkerProperties properties) {
        this.deliveryClaimer = Objects.requireNonNull(deliveryClaimer, "deliveryClaimer must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.permits = new Semaphore(properties.maxInFlightClaims(), true);
    }

    public List<BoundClaim> claimAvailable() {
        int reserved = reserveAvailablePermits();
        if (reserved == 0) {
            return List.of();
        }
        List<ClaimedDelivery> claims;
        try {
            claims = List.copyOf(deliveryClaimer.claim(reserved, properties.initialClaimLease()));
        } catch (RuntimeException exception) {
            permits.release(reserved);
            throw exception;
        }
        if (claims.size() > reserved) {
            permits.release(reserved);
            throw new IllegalStateException("delivery claimer returned more claims than reserved permits");
        }
        permits.release(reserved - claims.size());
        return claims.stream().map(claim -> new BoundClaim(claim, permits)).toList();
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    private int reserveAvailablePermits() {
        int reserved = 0;
        while (reserved < properties.maxInFlightClaims() && permits.tryAcquire()) {
            reserved++;
        }
        return reserved;
    }

    /**
     * The Group 7 task must close this in a {@code finally} block after it stops all work for the token.
     */
    public static final class BoundClaim implements AutoCloseable {

        private final ClaimedDelivery claim;
        private final Semaphore permits;
        private final AtomicBoolean closed = new AtomicBoolean();

        private BoundClaim(ClaimedDelivery claim, Semaphore permits) {
            this.claim = Objects.requireNonNull(claim, "claim must not be null");
            this.permits = permits;
        }

        public ClaimedDelivery claim() {
            return claim;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                permits.release();
            }
        }
    }
}
