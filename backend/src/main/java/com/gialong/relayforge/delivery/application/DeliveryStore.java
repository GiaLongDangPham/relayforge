package com.gialong.relayforge.delivery.application;

import com.gialong.relayforge.delivery.api.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.DispatchInstruction;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.gialong.relayforge.delivery.api.DeliveryDisplayStatus;
import com.gialong.relayforge.delivery.api.AttemptHistorySummary;
import com.gialong.relayforge.delivery.api.EventDeliverySummary;
import com.gialong.relayforge.delivery.api.DeliveryOperationalSnapshot;
import com.gialong.relayforge.delivery.api.RetentionCleanupResult;

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

    ClaimedDelivery claim(ClaimCandidate candidate, UUID claimToken, Duration initialLease);

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

    boolean hasCurrentLease(DispatchInstruction instruction, Duration minimumRemaining);

    boolean recordLateDiagnostic(DispatchInstruction instruction, AttemptCompletion completion, UUID diagnosticId);

    List<ExpiredStartedAttempt> lockExpiredStartedAttempts(int capacity);

    boolean recoverExpiredStartedAttempt(
            ExpiredStartedAttempt expiredAttempt,
            CompletionDecision decision
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
