package com.gialong.relayforge.runtime.deliveries;

import io.micrometer.core.instrument.MeterRegistry;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * API-mode dedicated PostgreSQL LISTEN connection. Its loss affects only live-update hints,
 * never request handling or delivery processing.
 */
@Component
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class PostgresDeliveryUpdateListener implements SmartLifecycle {

    private static final long INITIAL_RECONNECT_DELAY_MILLIS = 1_000;
    private static final long MAX_RECONNECT_DELAY_MILLIS = 10_000;
    private static final String CHANNEL = "relayforge_delivery_updates";

    private final DedicatedPostgresListenerProperties dataSourceProperties;
    private final DeliveryUpdateSseRegistry registry;
    private final MeterRegistry meterRegistry;
    private volatile boolean running;
    private volatile Connection activeConnection;
    private ExecutorService executor;

    PostgresDeliveryUpdateListener(
            DedicatedPostgresListenerProperties dataSourceProperties,
            DeliveryUpdateSseRegistry registry,
            MeterRegistry meterRegistry
    ) {
        this.dataSourceProperties = dataSourceProperties;
        this.registry = registry;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        if (!dataSourceProperties.isComplete()) {
            meter("disabled").increment();
            return;
        }
        running = true;
        executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("relayforge-pg-listen-", 0).factory());
        executor.execute(this::listenUntilStopped);
    }

    @Override
    public synchronized void stop() {
        running = false;
        closeActiveConnection();
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void listenUntilStopped() {
        long reconnectDelay = INITIAL_RECONNECT_DELAY_MILLIS;
        while (running) {
            try (Connection connection = DriverManager.getConnection(
                    dataSourceProperties.getUrl(),
                    dataSourceProperties.getUsername(),
                    dataSourceProperties.getPassword()
            )) {
                activeConnection = connection;
                try (Statement statement = connection.createStatement()) {
                    statement.execute("listen " + CHANNEL);
                }
                meter("connected").increment();
                reconnectDelay = INITIAL_RECONNECT_DELAY_MILLIS;
                PGConnection pgConnection = connection.unwrap(PGConnection.class);
                while (running) {
                    PGNotification[] notifications = pgConnection.getNotifications(1_000);
                    for (PGNotification notification : notifications == null ? new PGNotification[0] : notifications) {
                        parse(notification.getParameter()).ifPresent(update -> {
                            meter("received").increment();
                            registry.fanOut(update.projectId(), update.deliveryId(), Instant.now());
                        });
                    }
                }
            } catch (SQLException exception) {
                if (running) {
                    meter("reconnect").increment();
                    waitBeforeReconnect(reconnectDelay);
                    reconnectDelay = Math.min(MAX_RECONNECT_DELAY_MILLIS, reconnectDelay * 2);
                }
            } finally {
                activeConnection = null;
            }
        }
    }

    private Optional<DeliveryUpdate> parse(String payload) {
        if (payload == null) {
            meter("invalid_payload").increment();
            return Optional.empty();
        }
        String[] parts = payload.split(":", -1);
        if (parts.length != 2) {
            meter("invalid_payload").increment();
            return Optional.empty();
        }
        try {
            return Optional.of(new DeliveryUpdate(UUID.fromString(parts[0]), UUID.fromString(parts[1])));
        } catch (IllegalArgumentException exception) {
            meter("invalid_payload").increment();
            return Optional.empty();
        }
    }

    private void waitBeforeReconnect(long delayMillis) {
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeActiveConnection() {
        Connection connection = activeConnection;
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // A stop request is best-effort; the listener owns no delivery state.
        }
    }

    private io.micrometer.core.instrument.Counter meter(String outcome) {
        return meterRegistry.counter("relayforge.dashboard_updates.listener", "outcome", outcome);
    }

    private record DeliveryUpdate(UUID projectId, UUID deliveryId) {
    }
}
