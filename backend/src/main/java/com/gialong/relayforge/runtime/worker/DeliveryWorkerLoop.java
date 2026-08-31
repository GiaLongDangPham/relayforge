package com.gialong.relayforge.runtime.worker;
import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptRecovery;
import com.gialong.relayforge.delivery.api.processing.DeliveryClaimer;

import com.gialong.relayforge.delivery.api.processing.DeliveryAttemptRecovery;
import com.gialong.relayforge.delivery.api.processing.DeliveryClaimer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker-only polling adapter. PostgreSQL and the bounded permit coordinator remain the work authority.
 */
public final class DeliveryWorkerLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorkerLoop.class);
    private final WorkerClaimCoordinator claimCoordinator;
    private final DeliveryClaimer deliveryClaimer;
    private final DeliveryAttemptRecovery attemptRecovery;
    private final WorkerDeliveryProcessor processor;
    private final WorkerProperties properties;
    private final WorkerOperationalMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile ScheduledExecutorService scheduler;
    private volatile ExecutorService taskExecutor;

    public DeliveryWorkerLoop(
            WorkerClaimCoordinator claimCoordinator,
            DeliveryClaimer deliveryClaimer,
            DeliveryAttemptRecovery attemptRecovery,
            WorkerDeliveryProcessor processor,
            WorkerProperties properties,
            WorkerOperationalMetrics metrics
    ) {
        this.claimCoordinator = Objects.requireNonNull(claimCoordinator, "claimCoordinator must not be null");
        this.deliveryClaimer = Objects.requireNonNull(deliveryClaimer, "deliveryClaimer must not be null");
        this.attemptRecovery = Objects.requireNonNull(attemptRecovery, "attemptRecovery must not be null");
        this.processor = Objects.requireNonNull(processor, "processor must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("relayforge-worker-scheduler-", 0).factory());
        taskExecutor = Executors.newVirtualThreadPerTaskExecutor();
        metrics.workerStarted();
        schedulePoll(Duration.ZERO);
        scheduleRecovery(Duration.ZERO);
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ScheduledExecutorService currentScheduler = scheduler;
        if (currentScheduler != null) {
            currentScheduler.shutdownNow();
        }
        ExecutorService currentTaskExecutor = taskExecutor;
        if (currentTaskExecutor != null) {
            currentTaskExecutor.shutdown();
        }
        metrics.workerStopped();
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return 0;
    }

    private void schedulePoll(Duration delay) {
        ScheduledExecutorService currentScheduler = scheduler;
        if (running.get() && currentScheduler != null) {
            currentScheduler.schedule(() -> {
                if (!running.get()) {
                    return;
                }
                pollOnce();
                schedulePoll(properties.pollingInterval().plus(pollJitter()));
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void scheduleRecovery(Duration delay) {
        ScheduledExecutorService currentScheduler = scheduler;
        if (running.get() && currentScheduler != null) {
            currentScheduler.schedule(() -> {
                if (!running.get()) {
                    return;
                }
                recoverOnce();
                scheduleRecovery(properties.recoveryInterval());
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void pollOnce() {
        try {
            var claims = claimCoordinator.claimAvailable();
            metrics.recordClaims(claims.size());
            claims.forEach(this::submit);
        } catch (RuntimeException ignored) {
            metrics.recordClaimPollFailure();
            log.atWarn()
                    .addKeyValue("event", "delivery_claim_poll_failed")
                    .addKeyValue("runtimeMode", "worker")
                    .log("Delivery claim poll failed; a later bounded poll will retry");
            // PostgreSQL remains the source of truth; the next bounded poll retries claim acquisition.
        }
    }

    private void submit(WorkerClaimCoordinator.BoundClaim claim) {
        try {
            ExecutorService currentTaskExecutor = taskExecutor;
            if (!running.get() || currentTaskExecutor == null) {
                claim.close();
                return;
            }
            currentTaskExecutor.submit(() -> processor.process(claim));
        } catch (RejectedExecutionException exception) {
            claim.close();
            metrics.recordRejectedSubmission();
        }
    }

    private void recoverOnce() {
        try {
            int capacity = properties.maxInFlightClaims();
            metrics.recordRecovery("pre_attempt", deliveryClaimer.recoverExpiredPreAttemptClaims(capacity));
            metrics.recordRecovery("started_attempt", attemptRecovery.recoverExpiredStartedAttempts(capacity));
        } catch (RuntimeException ignored) {
            metrics.recordRecoveryFailure();
            log.atWarn()
                    .addKeyValue("event", "delivery_recovery_scan_failed")
                    .addKeyValue("runtimeMode", "worker")
                    .log("Delivery recovery scan failed; a later scan will retry");
            // A later scan may recover only still-current expired claims; no clock-local decision is made here.
        }
    }

    private Duration pollJitter() {
        long bound = properties.pollingJitter().toMillis();
        if (bound == 0) {
            return Duration.ZERO;
        }
        return Duration.ofMillis(java.util.concurrent.ThreadLocalRandom.current().nextLong(bound + 1));
    }
}
