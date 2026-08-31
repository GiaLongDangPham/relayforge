package com.gialong.relayforge.runtime.observability;
import com.gialong.relayforge.delivery.api.operations.DeliveryOperationalSnapshot;
import com.gialong.relayforge.delivery.api.operations.DeliveryOperationalSnapshotQuery;

import com.gialong.relayforge.delivery.api.operations.DeliveryOperationalSnapshot;
import com.gialong.relayforge.delivery.api.operations.DeliveryOperationalSnapshotQuery;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Publishes API-owned PostgreSQL backlog aggregates from a periodic, bounded query. Gauges read an
 * in-memory snapshot, so a Prometheus scrape never runs a database query once per time series.
 */
public final class DeliveryBacklogMetrics {

    private static final Logger log = LoggerFactory.getLogger(DeliveryBacklogMetrics.class);

    private final DeliveryOperationalSnapshotQuery snapshotQuery;
    private final AtomicReference<DeliveryOperationalSnapshot> snapshot = new AtomicReference<>(
            DeliveryOperationalSnapshot.empty()
    );
    private final Counter refreshFailures;

    public DeliveryBacklogMetrics(DeliveryOperationalSnapshotQuery snapshotQuery, MeterRegistry meterRegistry) {
        this.snapshotQuery = Objects.requireNonNull(snapshotQuery, "snapshotQuery must not be null");
        MeterRegistry requiredRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.refreshFailures = Counter.builder("relayforge.delivery.backlog.refresh.failures")
                .description("Failed attempts to refresh the in-memory delivery backlog snapshot")
                .register(requiredRegistry);
        registerGauges(requiredRegistry);
    }

    @Scheduled(fixedDelayString = "${relayforge.observability.backlog-refresh-interval:5s}")
    public void refresh() {
        try {
            snapshot.set(snapshotQuery.currentSnapshot());
        } catch (RuntimeException exception) {
            refreshFailures.increment();
            log.atWarn()
                    .addKeyValue("event", "delivery_backlog_refresh_failed")
                    .addKeyValue("runtimeMode", "api")
                    .log("Delivery backlog snapshot refresh failed");
        }
    }

    private void registerGauges(MeterRegistry meterRegistry) {
        Gauge.builder("relayforge.delivery.backlog", snapshot, current -> current.get().readyDueCount())
                .tag("state", "ready")
                .description("Due deliveries whose endpoints are currently enabled")
                .register(meterRegistry);
        Gauge.builder("relayforge.delivery.backlog", snapshot, current -> current.get().pausedDueCount())
                .tag("state", "paused")
                .description("Due deliveries paused by a disabled endpoint")
                .register(meterRegistry);
        Gauge.builder("relayforge.delivery.claimed", snapshot, current -> current.get().claimedCount())
                .description("Deliveries currently owned by a worker claim lease")
                .register(meterRegistry);
        Gauge.builder("relayforge.delivery.oldest_ready_due.age", snapshot, DeliveryBacklogMetrics::oldestReadyDueAgeSeconds)
                .baseUnit("seconds")
                .description("Age of the oldest currently due enabled delivery")
                .register(meterRegistry);
    }

    private static double oldestReadyDueAgeSeconds(AtomicReference<DeliveryOperationalSnapshot> current) {
        Instant oldestDueAt = current.get().oldestReadyDueAt();
        if (oldestDueAt == null) {
            return 0;
        }
        return Math.max(0, Duration.between(oldestDueAt, Instant.now()).toMillis() / 1000.0);
    }
}
