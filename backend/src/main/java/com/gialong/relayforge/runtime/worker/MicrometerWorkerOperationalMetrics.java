package com.gialong.relayforge.runtime.worker;

import com.gialong.relayforge.delivery.api.AttemptFinalizationResult;
import com.gialong.relayforge.delivery.api.DispatchObservation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Micrometer adapter with bounded tags only; it never tags a metric with tenant or delivery identity. */
public final class MicrometerWorkerOperationalMetrics implements WorkerOperationalMetrics {

    private final MeterRegistry meterRegistry;
    private final AtomicBoolean running = new AtomicBoolean();

    public MicrometerWorkerOperationalMetrics(MeterRegistry meterRegistry, WorkerClaimCoordinator claimCoordinator) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        WorkerClaimCoordinator requiredCoordinator = Objects.requireNonNull(
                claimCoordinator,
                "claimCoordinator must not be null"
        );
        Gauge.builder("relayforge.worker.running", running, value -> value.get() ? 1 : 0)
                .description("Whether the worker lifecycle is accepting scheduled polling work")
                .register(meterRegistry);
        Gauge.builder("relayforge.worker.permits.available", requiredCoordinator, WorkerClaimCoordinator::availablePermits)
                .description("Unreserved local worker dispatch permits")
                .register(meterRegistry);
    }

    @Override
    public void workerStarted() {
        running.set(true);
    }

    @Override
    public void workerStopped() {
        running.set(false);
    }

    @Override
    public void recordClaims(int count) {
        if (count > 0) {
            Counter.builder("relayforge.worker.claimed")
                    .description("Deliveries claimed by this worker instance")
                    .register(meterRegistry)
                    .increment(count);
        }
    }

    @Override
    public void recordClaimPollFailure() {
        counter("relayforge.worker.poll.failures").increment();
    }

    @Override
    public void recordRejectedSubmission() {
        counter("relayforge.worker.submission.rejected").increment();
    }

    @Override
    public void recordRecovery(String stage, int count) {
        if (count > 0) {
            Counter.builder("relayforge.worker.recovered")
                    .tag("stage", stage)
                    .description("Expired delivery work recovered by this worker")
                    .register(meterRegistry)
                    .increment(count);
        }
    }

    @Override
    public void recordRecoveryFailure() {
        counter("relayforge.worker.recovery.failures").increment();
    }

    @Override
    public void recordDispatch(DispatchObservation observation) {
        DispatchObservation requiredObservation = Objects.requireNonNull(observation, "observation must not be null");
        String outcome = lowercase(requiredObservation.outcome().name());
        String failureCode = requiredObservation.failureCode().map(code -> lowercase(code.name())).orElse("none");
        Counter.builder("relayforge.delivery.attempts")
                .tag("outcome", outcome)
                .tag("failure_code", failureCode)
                .description("Completed dispatch cycles by bounded outcome")
                .register(meterRegistry)
                .increment();
        Timer.builder("relayforge.delivery.dispatch")
                .tag("outcome", outcome)
                .publishPercentileHistogram()
                .description("Duration of an outbound dispatch cycle, including prevented dispatches")
                .register(meterRegistry)
                .record(requiredObservation.duration());
    }

    @Override
    public void recordFinalization(AttemptFinalizationResult result) {
        Counter.builder("relayforge.delivery.finalization")
                .tag("result", lowercase(Objects.requireNonNull(result, "result must not be null").name()))
                .description("Durable finalization outcomes after a completed dispatch")
                .register(meterRegistry)
                .increment();
    }

    @Override
    public void recordFinalizationAbandoned() {
        counter("relayforge.delivery.finalization.abandoned").increment();
    }

    private Counter counter(String name) {
        return Counter.builder(name).register(meterRegistry);
    }

    private static String lowercase(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
