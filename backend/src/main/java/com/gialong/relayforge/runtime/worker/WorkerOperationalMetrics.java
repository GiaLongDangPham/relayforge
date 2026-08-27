package com.gialong.relayforge.runtime.worker;

import com.gialong.relayforge.delivery.api.AttemptFinalizationResult;
import com.gialong.relayforge.delivery.api.DispatchObservation;

/** Bounded, instance-local worker signals. Persistent delivery history remains the system of record. */
public interface WorkerOperationalMetrics {

    void workerStarted();

    void workerStopped();

    void recordClaims(int count);

    void recordClaimPollFailure();

    void recordRejectedSubmission();

    void recordRecovery(String stage, int count);

    void recordRecoveryFailure();

    void recordDispatch(DispatchObservation observation);

    void recordFinalization(AttemptFinalizationResult result);

    void recordFinalizationAbandoned();

    static WorkerOperationalMetrics noop() {
        return new WorkerOperationalMetrics() {
            @Override public void workerStarted() { }
            @Override public void workerStopped() { }
            @Override public void recordClaims(int count) { }
            @Override public void recordClaimPollFailure() { }
            @Override public void recordRejectedSubmission() { }
            @Override public void recordRecovery(String stage, int count) { }
            @Override public void recordRecoveryFailure() { }
            @Override public void recordDispatch(DispatchObservation observation) { }
            @Override public void recordFinalization(AttemptFinalizationResult result) { }
            @Override public void recordFinalizationAbandoned() { }
        };
    }
}
