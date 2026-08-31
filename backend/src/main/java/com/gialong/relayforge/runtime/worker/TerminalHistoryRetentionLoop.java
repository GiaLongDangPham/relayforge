package com.gialong.relayforge.runtime.worker;
import com.gialong.relayforge.delivery.api.operations.RetentionCleanupResult;
import com.gialong.relayforge.delivery.api.operations.TerminalHistoryRetention;

import com.gialong.relayforge.delivery.api.operations.RetentionCleanupResult;
import com.gialong.relayforge.delivery.api.operations.TerminalHistoryRetention;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Worker-only fixed-delay adapter; each retained event graph still has its own short transaction. */
public final class TerminalHistoryRetentionLoop implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TerminalHistoryRetentionLoop.class);

    private final TerminalHistoryRetention retention;
    private final RetentionProperties properties;
    private final WorkerOperationalMetrics metrics;
    private final AtomicBoolean running = new AtomicBoolean();

    private volatile ScheduledExecutorService scheduler;

    public TerminalHistoryRetentionLoop(
            TerminalHistoryRetention retention,
            RetentionProperties properties,
            WorkerOperationalMetrics metrics
    ) {
        this.retention = Objects.requireNonNull(retention, "retention must not be null");
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("relayforge-retention-scheduler-", 0).factory()
        );
        scheduler.scheduleWithFixedDelay(
                this::cleanOnce,
                properties.initialDelay().toMillis(),
                properties.cleanupInterval().toMillis(),
                TimeUnit.MILLISECONDS
        );
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

    private void cleanOnce() {
        try {
            RetentionCleanupResult result = retention.cleanExpiredTerminalHistory(
                    properties.terminalHistoryDays(),
                    properties.maxGraphsPerRun()
            );
            metrics.recordRetentionCleanup(result);
            if (result.deletedAnything()) {
                log.atInfo()
                        .addKeyValue("event", "terminal_history_retention_completed")
                        .addKeyValue("eventsDeleted", result.eventsDeleted())
                        .addKeyValue("deliveriesDeleted", result.deliveriesDeleted())
                        .addKeyValue("attemptsDeleted", result.attemptsDeleted())
                        .addKeyValue("lateDiagnosticsDeleted", result.lateDiagnosticsDeleted())
                        .addKeyValue("replayRequestsDeleted", result.replayRequestsDeleted())
                        .log("Terminal history retention completed");
            }
        } catch (RuntimeException exception) {
            metrics.recordRetentionFailure();
            log.atWarn()
                    .addKeyValue("event", "terminal_history_retention_failed")
                    .addKeyValue("runtimeMode", "worker")
                    .setCause(exception)
                    .log("Terminal history retention failed; the next bounded run will retry");
        }
    }
}
