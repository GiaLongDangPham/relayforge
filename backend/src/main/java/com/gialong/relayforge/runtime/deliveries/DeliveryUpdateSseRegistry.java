package com.gialong.relayforge.runtime.deliveries;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** API-local, project-keyed SSE fan-out. It intentionally retains no delivery state or replay buffer. */
@Component
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class DeliveryUpdateSseRegistry implements SmartLifecycle {

    private static final long STREAM_TIMEOUT_MILLIS = Duration.ofMinutes(15).toMillis();
    private final ConcurrentMap<UUID, Set<SseEmitter>> emittersByProject = new ConcurrentHashMap<>();
    private final MeterRegistry meterRegistry;
    private volatile boolean running;

    DeliveryUpdateSseRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;
        for (var entry : emittersByProject.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                remove(entry.getKey(), emitter, "closed");
                emitter.complete();
            }
        }
        emittersByProject.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    SseEmitter open(UUID projectId) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MILLIS);
        emittersByProject.computeIfAbsent(projectId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        meter("opened").increment();
        emitter.onCompletion(() -> remove(projectId, emitter, "closed"));
        emitter.onTimeout(() -> remove(projectId, emitter, "timed_out"));
        emitter.onError(ignored -> remove(projectId, emitter, "failed"));
        try {
            emitter.send(SseEmitter.event().comment("connected"));
        } catch (IOException | IllegalStateException exception) {
            remove(projectId, emitter, "open_failed");
            emitter.complete();
        }
        return emitter;
    }

    void fanOut(UUID projectId, UUID deliveryId, Instant observedAt) {
        Set<SseEmitter> emitters = emittersByProject.get(projectId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        DeliveryUpdateResponse response = new DeliveryUpdateResponse(projectId, deliveryId, observedAt);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("delivery.changed").data(response));
                meter("sent").increment();
            } catch (IOException | IllegalStateException exception) {
                remove(projectId, emitter, "send_failed");
                emitter.complete();
            }
        }
    }

    @Scheduled(fixedDelay = 15_000)
    void sendHeartbeats() {
        for (var entry : emittersByProject.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                try {
                    emitter.send(SseEmitter.event().comment("keepalive"));
                } catch (IOException | IllegalStateException exception) {
                    remove(entry.getKey(), emitter, "heartbeat_failed");
                    emitter.complete();
                }
            }
        }
    }

    private void remove(UUID projectId, SseEmitter emitter, String outcome) {
        AtomicBoolean removed = new AtomicBoolean();
        emittersByProject.computeIfPresent(projectId, (ignored, emitters) -> {
            if (emitters.remove(emitter)) {
                removed.set(true);
            }
            return emitters.isEmpty() ? null : emitters;
        });
        if (removed.get()) {
            meter(outcome).increment();
        }
    }

    private io.micrometer.core.instrument.Counter meter(String outcome) {
        return meterRegistry.counter("relayforge.dashboard_updates.streams", "outcome", outcome);
    }

    record DeliveryUpdateResponse(UUID projectId, UUID deliveryId, Instant observedAt) {
    }
}
