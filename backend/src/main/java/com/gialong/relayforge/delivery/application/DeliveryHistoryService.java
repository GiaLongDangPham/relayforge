package com.gialong.relayforge.delivery.application;
import com.gialong.relayforge.delivery.api.history.AttemptHistoryDetails;
import com.gialong.relayforge.delivery.api.history.AttemptHistorySummary;
import com.gialong.relayforge.delivery.api.history.DeliveryDisplayStatus;
import com.gialong.relayforge.delivery.api.history.DeliveryEndpointMetadata;
import com.gialong.relayforge.delivery.api.history.DeliveryHistory;
import com.gialong.relayforge.delivery.api.history.DeliveryHistoryDetails;
import com.gialong.relayforge.delivery.api.history.DeliveryHistoryPage;
import com.gialong.relayforge.delivery.api.history.DeliveryHistorySummary;
import com.gialong.relayforge.delivery.api.history.EventHistoryDetails;
import com.gialong.relayforge.delivery.api.history.EventHistoryPage;
import com.gialong.relayforge.delivery.api.history.EventHistorySummary;
import com.gialong.relayforge.delivery.api.history.LateAttemptDiagnostic;

import com.gialong.relayforge.delivery.api.history.AttemptHistoryDetails;
import com.gialong.relayforge.delivery.api.history.AttemptHistorySummary;
import com.gialong.relayforge.delivery.api.history.DeliveryDisplayStatus;
import com.gialong.relayforge.delivery.api.history.DeliveryEndpointMetadata;
import com.gialong.relayforge.delivery.api.history.DeliveryHistory;
import com.gialong.relayforge.delivery.api.history.DeliveryHistoryDetails;
import com.gialong.relayforge.delivery.api.history.DeliveryHistoryPage;
import com.gialong.relayforge.delivery.api.history.DeliveryHistorySummary;
import com.gialong.relayforge.delivery.api.history.EventHistoryDetails;
import com.gialong.relayforge.delivery.api.history.EventHistoryPage;
import com.gialong.relayforge.delivery.api.history.EventHistorySummary;
import com.gialong.relayforge.delivery.api.history.LateAttemptDiagnostic;
import com.gialong.relayforge.endpoint.api.EndpointHistoryMetadata;
import com.gialong.relayforge.endpoint.api.EndpointHistoryQuery;
import com.gialong.relayforge.project.api.ProjectCatalog;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Owns project-authorized history composition. Endpoint state is read through its public query, never its tables.
 */
@Service
final class DeliveryHistoryService implements DeliveryHistory {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_RESPONSE_PREVIEW_BYTES = 8 * 1024;

    private final ProjectCatalog projectCatalog;
    private final EndpointHistoryQuery endpointHistoryQuery;
    private final DeliveryStore deliveryStore;
    private final TransactionTemplate readTransaction;

    DeliveryHistoryService(
            ProjectCatalog projectCatalog,
            EndpointHistoryQuery endpointHistoryQuery,
            DeliveryStore deliveryStore,
            PlatformTransactionManager transactionManager
    ) {
        this.projectCatalog = Objects.requireNonNull(projectCatalog, "projectCatalog must not be null");
        this.endpointHistoryQuery = Objects.requireNonNull(endpointHistoryQuery, "endpointHistoryQuery must not be null");
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore must not be null");
        this.readTransaction = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager must not be null")
        );
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public Optional<EventHistoryPage> listEvents(UUID ownerId, UUID projectId, String eventType, int limit, String cursor) {
        UUID requiredOwnerId = requireId(ownerId, "ownerId");
        UUID requiredProjectId = requireId(projectId, "projectId");
        int requiredLimit = requireLimit(limit);
        String normalizedEventType = eventType == null ? null : PublishEventType.requireNormalized(eventType);
        EventHistoryCursor position = cursor == null
                ? null
                : EventHistoryCursor.decodeForQuery(requiredOwnerId, requiredProjectId, normalizedEventType, cursor);
        return inReadTransaction(() -> {
            if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()) {
                return Optional.empty();
            }
            List<HistoryRecords.EventRecord> fetched = deliveryStore.listHistoryEvents(
                    requiredProjectId,
                    normalizedEventType,
                    position,
                    requiredLimit + 1
            );
            boolean hasMore = fetched.size() > requiredLimit;
            List<HistoryRecords.EventRecord> records = hasMore ? fetched.subList(0, requiredLimit) : fetched;
            List<EventHistorySummary> items = records.stream().map(DeliveryHistoryService::eventSummary).toList();
            String nextCursor = hasMore
                    ? EventHistoryCursor.encode(eventCursor(requiredOwnerId, requiredProjectId, normalizedEventType, records.getLast()))
                    : null;
            return Optional.of(new EventHistoryPage(items, nextCursor));
        });
    }

    @Override
    public Optional<EventHistoryDetails> findEvent(UUID ownerId, UUID projectId, UUID eventId) {
        UUID requiredOwnerId = requireId(ownerId, "ownerId");
        UUID requiredProjectId = requireId(projectId, "projectId");
        UUID requiredEventId = requireId(eventId, "eventId");
        return inReadTransaction(() -> {
            if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()) {
                return Optional.empty();
            }
            return deliveryStore.findHistoryEvent(requiredProjectId, requiredEventId)
                    .map(event -> new EventHistoryDetails(
                            eventSummary(event),
                            event.payloadJson(),
                            deliveryStore.summarizeEventDeliveries(requiredProjectId, requiredEventId)
                    ));
        });
    }

    @Override
    public Optional<DeliveryHistoryPage> listEventDeliveries(
            UUID ownerId,
            UUID projectId,
            UUID eventId,
            int limit,
            String cursor
    ) {
        UUID requiredOwnerId = requireId(ownerId, "ownerId");
        UUID requiredProjectId = requireId(projectId, "projectId");
        UUID requiredEventId = requireId(eventId, "eventId");
        int requiredLimit = requireLimit(limit);
        DeliveryHistoryCursor position = cursor == null
                ? null
                : DeliveryHistoryCursor.decodeForQuery(
                        DeliveryHistoryCursor.QueryKind.EVENT_DELIVERIES_ASC,
                        requiredOwnerId,
                        requiredProjectId,
                        requiredEventId,
                        null,
                        null,
                        cursor
                );
        return inReadTransaction(() -> {
            if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()
                    || deliveryStore.findHistoryEvent(requiredProjectId, requiredEventId).isEmpty()) {
                return Optional.empty();
            }
            List<HistoryRecords.DeliveryRecord> fetched = deliveryStore.listEventHistoryDeliveries(
                    requiredProjectId,
                    requiredEventId,
                    position,
                    requiredLimit + 1
            );
            return Optional.of(deliveryPage(
                    requiredOwnerId,
                    requiredProjectId,
                    DeliveryHistoryCursor.QueryKind.EVENT_DELIVERIES_ASC,
                    requiredEventId,
                    null,
                    null,
                    fetched,
                    requiredLimit
            ));
        });
    }

    @Override
    public Optional<DeliveryHistoryPage> listDeliveries(
            UUID ownerId,
            UUID projectId,
            UUID eventId,
            UUID endpointId,
            DeliveryDisplayStatus displayStatus,
            int limit,
            String cursor
    ) {
        UUID requiredOwnerId = requireId(ownerId, "ownerId");
        UUID requiredProjectId = requireId(projectId, "projectId");
        int requiredLimit = requireLimit(limit);
        DeliveryHistoryCursor position = cursor == null
                ? null
                : DeliveryHistoryCursor.decodeForQuery(
                        DeliveryHistoryCursor.QueryKind.PROJECT_DELIVERIES_DESC,
                        requiredOwnerId,
                        requiredProjectId,
                        eventId,
                        endpointId,
                        displayStatus,
                        cursor
                );
        return inReadTransaction(() -> {
            if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()) {
                return Optional.empty();
            }
            Set<UUID> enabledEndpointIds = endpointHistoryQuery.findEnabledEndpointIds(requiredProjectId);
            List<HistoryRecords.DeliveryRecord> fetched = deliveryStore.listProjectHistoryDeliveries(
                    requiredProjectId,
                    eventId,
                    endpointId,
                    displayStatus,
                    enabledEndpointIds,
                    position,
                    requiredLimit + 1
            );
            return Optional.of(deliveryPage(
                    requiredOwnerId,
                    requiredProjectId,
                    DeliveryHistoryCursor.QueryKind.PROJECT_DELIVERIES_DESC,
                    eventId,
                    endpointId,
                    displayStatus,
                    fetched,
                    requiredLimit,
                    enabledEndpointIds
            ));
        });
    }

    @Override
    public Optional<DeliveryHistoryDetails> findDelivery(UUID ownerId, UUID projectId, UUID deliveryId) {
        UUID requiredOwnerId = requireId(ownerId, "ownerId");
        UUID requiredProjectId = requireId(projectId, "projectId");
        UUID requiredDeliveryId = requireId(deliveryId, "deliveryId");
        return inReadTransaction(() -> {
            if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()) {
                return Optional.empty();
            }
            Optional<HistoryRecords.DeliveryDetailRecord> record = deliveryStore.findHistoryDelivery(
                    requiredProjectId,
                    requiredDeliveryId
            );
            if (record.isEmpty()) {
                return Optional.empty();
            }
            HistoryRecords.DeliveryDetailRecord detail = record.orElseThrow();
            EndpointHistoryMetadata endpoint = endpointHistoryQuery.findHistoryMetadata(
                    requiredProjectId,
                    Set.of(detail.delivery().endpointId())
            ).get(detail.delivery().endpointId());
            if (endpoint == null) {
                throw new IllegalStateException("delivery endpoint history metadata is missing");
            }
            return Optional.of(new DeliveryHistoryDetails(
                    deliverySummary(detail.delivery(), Set.of(endpoint.endpointId()).stream()
                            .filter(ignored -> endpoint.enabled()).collect(java.util.stream.Collectors.toSet())),
                    detail.eventType(),
                    new DeliveryEndpointMetadata(endpoint.endpointId(), endpoint.name(), endpoint.enabled()),
                    deliveryStore.findReplayDeliveryIds(requiredProjectId, requiredDeliveryId),
                    detail.latestAttempt()
            ));
        });
    }

    @Override
    public Optional<List<AttemptHistorySummary>> listAttempts(UUID ownerId, UUID projectId, UUID deliveryId) {
        UUID requiredOwnerId = requireId(ownerId, "ownerId");
        UUID requiredProjectId = requireId(projectId, "projectId");
        UUID requiredDeliveryId = requireId(deliveryId, "deliveryId");
        return inReadTransaction(() -> {
            if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()
                    || deliveryStore.findHistoryDelivery(requiredProjectId, requiredDeliveryId).isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(List.copyOf(deliveryStore.listHistoryAttempts(requiredProjectId, requiredDeliveryId)));
        });
    }

    @Override
    public Optional<AttemptHistoryDetails> findAttempt(UUID ownerId, UUID projectId, UUID deliveryId, UUID attemptId) {
        UUID requiredOwnerId = requireId(ownerId, "ownerId");
        UUID requiredProjectId = requireId(projectId, "projectId");
        UUID requiredDeliveryId = requireId(deliveryId, "deliveryId");
        UUID requiredAttemptId = requireId(attemptId, "attemptId");
        return inReadTransaction(() -> {
            if (projectCatalog.findOwned(requiredOwnerId, requiredProjectId).isEmpty()
                    || deliveryStore.findHistoryDelivery(requiredProjectId, requiredDeliveryId).isEmpty()) {
                return Optional.empty();
            }
            return deliveryStore.findHistoryAttempt(requiredProjectId, requiredDeliveryId, requiredAttemptId)
                    .map(DeliveryHistoryService::attemptDetails);
        });
    }

    private DeliveryHistoryPage deliveryPage(
            UUID ownerId,
            UUID projectId,
            DeliveryHistoryCursor.QueryKind queryKind,
            UUID eventId,
            UUID endpointId,
            DeliveryDisplayStatus displayStatus,
            List<HistoryRecords.DeliveryRecord> fetched,
            int limit
    ) {
        return deliveryPage(ownerId, projectId, queryKind, eventId, endpointId, displayStatus, fetched, limit,
                endpointHistoryQuery.findEnabledEndpointIds(projectId));
    }

    private DeliveryHistoryPage deliveryPage(
            UUID ownerId,
            UUID projectId,
            DeliveryHistoryCursor.QueryKind queryKind,
            UUID eventId,
            UUID endpointId,
            DeliveryDisplayStatus displayStatus,
            List<HistoryRecords.DeliveryRecord> fetched,
            int limit,
            Set<UUID> enabledEndpointIds
    ) {
        boolean hasMore = fetched.size() > limit;
        List<HistoryRecords.DeliveryRecord> records = hasMore ? fetched.subList(0, limit) : fetched;
        List<DeliveryHistorySummary> items = records.stream()
                .map(record -> deliverySummary(record, enabledEndpointIds))
                .toList();
        String nextCursor = hasMore
                ? DeliveryHistoryCursor.encode(new DeliveryHistoryCursor(
                        queryKind,
                        ownerId,
                        projectId,
                        eventId,
                        endpointId,
                        displayStatus,
                        records.getLast().createdAt(),
                        records.getLast().id()
                ))
                : null;
        return new DeliveryHistoryPage(items, nextCursor);
    }

    private static EventHistorySummary eventSummary(HistoryRecords.EventRecord event) {
        return new EventHistorySummary(event.id(), event.eventType(), event.acceptedAt(), event.deliveryCount());
    }

    private static EventHistoryCursor eventCursor(
            UUID ownerId,
            UUID projectId,
            String eventType,
            HistoryRecords.EventRecord event
    ) {
        return new EventHistoryCursor(ownerId, projectId, eventType, event.acceptedAt(), event.id());
    }

    private static DeliveryHistorySummary deliverySummary(
            HistoryRecords.DeliveryRecord delivery,
            Set<UUID> enabledEndpointIds
    ) {
        return new DeliveryHistorySummary(
                delivery.id(),
                delivery.eventId(),
                delivery.endpointId(),
                delivery.replayOfDeliveryId(),
                delivery.state(),
                displayStatus(delivery, enabledEndpointIds),
                delivery.attemptCount(),
                delivery.dueAt(),
                delivery.createdAt(),
                delivery.terminalAt()
        );
    }

    private static DeliveryDisplayStatus displayStatus(
            HistoryRecords.DeliveryRecord delivery,
            Set<UUID> enabledEndpointIds
    ) {
        return switch (delivery.state()) {
            case SUCCEEDED -> DeliveryDisplayStatus.SUCCEEDED;
            case FAILED_PERMANENT -> DeliveryDisplayStatus.FAILED_PERMANENT;
            case EXHAUSTED -> DeliveryDisplayStatus.EXHAUSTED;
            case CLAIMED -> enabledEndpointIds.contains(delivery.endpointId())
                    ? DeliveryDisplayStatus.CLAIMED
                    : DeliveryDisplayStatus.PAUSED;
            case PENDING -> {
                if (!enabledEndpointIds.contains(delivery.endpointId())) {
                    yield DeliveryDisplayStatus.PAUSED;
                }
                yield delivery.retryScheduled() ? DeliveryDisplayStatus.RETRY_SCHEDULED : DeliveryDisplayStatus.PENDING;
            }
        };
    }

    private static AttemptHistoryDetails attemptDetails(HistoryRecords.AttemptDetailRecord detail) {
        HistoryRecords.LateDiagnosticRecord diagnostic = detail.lateDiagnostic();
        return new AttemptHistoryDetails(
                detail.summary(),
                detail.destinationFingerprintVersion(),
                Base64.getUrlEncoder().withoutPadding().encodeToString(detail.destinationFingerprint()),
                escapedResponsePreview(detail.responsePreview()),
                detail.responseTruncated(),
                diagnostic == null ? null : new LateAttemptDiagnostic(
                        diagnostic.observedStatus(),
                        diagnostic.httpStatus(),
                        diagnostic.failureCode(),
                        diagnostic.latencyMilliseconds(),
                        diagnostic.observedAt()
                )
        );
    }

    private static String escapedResponsePreview(byte[] responsePreview) {
        if (responsePreview == null) {
            return null;
        }
        int size = Math.min(responsePreview.length, MAX_RESPONSE_PREVIEW_BYTES);
        String value = new String(responsePreview, 0, size, StandardCharsets.UTF_8);
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            escaped.append(switch (value.charAt(index)) {
                case '&' -> "&amp;";
                case '<' -> "&lt;";
                case '>' -> "&gt;";
                case '"' -> "&quot;";
                case '\'' -> "&#39;";
                default -> String.valueOf(value.charAt(index));
            });
        }
        return escaped.toString();
    }

    private <T> Optional<T> inReadTransaction(ReadWork<T> work) {
        return Objects.requireNonNull(
                readTransaction.execute(status -> work.execute()),
                "history read transaction returned no result"
        );
    }

    private static int requireLimit(int limit) {
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        return limit;
    }

    private static UUID requireId(UUID value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    @FunctionalInterface
    private interface ReadWork<T> {
        Optional<T> execute();
    }
}
