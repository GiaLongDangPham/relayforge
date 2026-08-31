package com.gialong.relayforge.delivery.application;
import com.gialong.relayforge.delivery.api.history.AttemptHistorySummary;
import com.gialong.relayforge.delivery.api.history.DeliveryDisplayStatus;
import com.gialong.relayforge.delivery.api.history.EventDeliverySummary;
import com.gialong.relayforge.delivery.api.operations.DeliveryOperationalSnapshot;
import com.gialong.relayforge.delivery.api.operations.RetentionCleanupResult;
import com.gialong.relayforge.delivery.api.processing.CircuitBreakerSettings;
import com.gialong.relayforge.delivery.api.processing.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.processing.DispatchInstruction;

import com.gialong.relayforge.delivery.api.processing.CircuitBreakerSettings;
import com.gialong.relayforge.delivery.api.processing.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.history.DeliveryDisplayStatus;
import com.gialong.relayforge.delivery.api.operations.DeliveryOperationalSnapshot;
import com.gialong.relayforge.delivery.api.processing.DispatchInstruction;
import com.gialong.relayforge.delivery.api.history.AttemptHistorySummary;
import com.gialong.relayforge.delivery.api.history.EventDeliverySummary;
import com.gialong.relayforge.delivery.api.operations.RetentionCleanupResult;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence boundary for immutable event acceptance, delivery work, and durable attempt-start state.
 */
public interface DeliveryStore {

    Optional<StoredEvent> insertEventIfAbsent(NewEvent event);

    Optional<StoredEvent> findEventByProjectAndIdempotencyKey(UUID projectId, String idempotencyKey);

    boolean eventHasEquivalentCommand(UUID eventId, String eventType, String payloadJson);

    void insertOriginalDeliveries(UUID projectId, UUID eventId, List<PendingDelivery> deliveries);

    int countOriginalDeliveries(UUID eventId);

    List<ClaimCandidate> lockDuePendingForEnabledEndpoints(Collection<UUID> enabledEndpointIds, int capacity);

    Optional<ClaimedDelivery> claim(
            ClaimCandidate candidate,
            UUID claimToken,
            Duration initialLease
    );

    int recoverExpiredPreAttemptClaims(int capacity);

    Optional<AttemptStartCandidate> lockCurrentClaimForAttemptStart(ClaimedDelivery claim);

    boolean releaseClaimBeforeAttempt(AttemptStartCandidate candidate);

    Optional<StartedAttempt> startAttempt(
            AttemptStartCandidate candidate,
            UUID attemptId,
            short destinationFingerprintVersion,
            byte[] destinationFingerprint,
            Duration attemptExecutionLease
    );

    boolean finalizeCurrentAttempt(
            DispatchInstruction instruction,
            AttemptCompletion completion,
            CompletionDecision decision
    );

    void applyCircuitAfterObservedFinalization(
            DispatchInstruction instruction,
            boolean qualifyingFailure,
            CircuitBreakerSettings settings
    );

    boolean hasCurrentLease(DispatchInstruction instruction, Duration minimumRemaining);

    boolean recordLateDiagnostic(DispatchInstruction instruction, AttemptCompletion completion, UUID diagnosticId);

    List<ExpiredStartedAttempt> lockExpiredStartedAttempts(int capacity);

    boolean recoverExpiredStartedAttempt(
            ExpiredStartedAttempt expiredAttempt,
            CompletionDecision decision
    );

    void reopenCircuitForRecoveredHalfOpenProbe(
            ExpiredStartedAttempt expiredAttempt,
            CircuitBreakerSettings settings
    );

    DeliveryOperationalSnapshot currentOperationalSnapshot();

    Optional<UUID> findNextExpiredTerminalEvent(int retentionDays);

    boolean tryLockRetentionGraph(UUID eventId);

    boolean lockRetentionEvent(UUID eventId);

    boolean isExpiredCompleteTerminalGraph(UUID eventId, int retentionDays);

    RetentionCleanupResult deleteRetentionGraph(UUID eventId);

    List<HistoryRecords.EventRecord> listHistoryEvents(
            UUID projectId,
            String eventType,
            EventHistoryCursor cursor,
            int fetchLimit
    );

    Optional<HistoryRecords.EventRecord> findHistoryEvent(UUID projectId, UUID eventId);

    EventDeliverySummary summarizeEventDeliveries(UUID projectId, UUID eventId);

    List<HistoryRecords.DeliveryRecord> listEventHistoryDeliveries(
            UUID projectId,
            UUID eventId,
            DeliveryHistoryCursor cursor,
            int fetchLimit
    );

    List<HistoryRecords.DeliveryRecord> listProjectHistoryDeliveries(
            UUID projectId,
            UUID eventId,
            UUID endpointId,
            DeliveryDisplayStatus displayStatus,
            Collection<UUID> enabledEndpointIds,
            DeliveryHistoryCursor cursor,
            int fetchLimit
    );

    Optional<HistoryRecords.DeliveryDetailRecord> findHistoryDelivery(UUID projectId, UUID deliveryId);

    List<UUID> findReplayDeliveryIds(UUID projectId, UUID deliveryId);

    List<AttemptHistorySummary> listHistoryAttempts(UUID projectId, UUID deliveryId);

    Optional<HistoryRecords.AttemptDetailRecord> findHistoryAttempt(UUID projectId, UUID deliveryId, UUID attemptId);

    HistoryRecords.ReplayResult replay(
            UUID projectId,
            UUID sourceDeliveryId,
            String idempotencyKey,
            UUID replayRequestId,
            UUID replayDeliveryId
    );
}
