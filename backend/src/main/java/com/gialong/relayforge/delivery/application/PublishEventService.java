package com.gialong.relayforge.delivery.application;

import com.gialong.relayforge.delivery.api.EventPublisher;
import com.gialong.relayforge.delivery.api.PublishEventResult;
import com.gialong.relayforge.delivery.api.PublishIdempotencyConflictException;
import com.gialong.relayforge.endpoint.api.EndpointRoutingQuery;
import com.gialong.relayforge.endpoint.api.RoutingEndpoint;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
final class PublishEventService implements EventPublisher {

    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private final DeliveryStore deliveryStore;
    private final EndpointRoutingQuery endpointRoutingQuery;
    private final PublishCommandFingerprint fingerprint;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transaction;

    PublishEventService(
            DeliveryStore deliveryStore,
            EndpointRoutingQuery endpointRoutingQuery,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager
    ) {
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore must not be null");
        this.endpointRoutingQuery = Objects.requireNonNull(endpointRoutingQuery, "endpointRoutingQuery must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.fingerprint = new PublishCommandFingerprint(this.objectMapper);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public PublishEventResult publish(UUID projectId, String idempotencyKey, String eventType, String payloadJson) {
        UUID requiredProjectId = Objects.requireNonNull(projectId, "projectId must not be null");
        String normalizedKey = PublishIdempotencyKey.requireValid(idempotencyKey);
        String normalizedEventType = PublishEventType.requireNormalized(eventType);
        JsonNode requiredPayload = parsePayload(payloadJson);
        if (fingerprint.canonicalPayloadSize(requiredPayload) > MAX_PAYLOAD_BYTES) {
            throw new IllegalArgumentException("payload must not exceed 64 KiB");
        }
        byte[] commandFingerprint = fingerprint.fingerprint(normalizedEventType, requiredPayload);
        try {
            NewEvent command = new NewEvent(
                    UUID.randomUUID(),
                    requiredProjectId,
                    normalizedEventType,
                    requiredPayload,
                    normalizedKey,
                    PublishCommandFingerprint.VERSION,
                    commandFingerprint
            );
            return Objects.requireNonNull(
                    transaction.execute(status -> accept(command)),
                    "publish transaction returned no result"
            );
        } finally {
            Arrays.fill(commandFingerprint, (byte) 0);
        }
    }

    private PublishEventResult accept(NewEvent command) {
        Optional<StoredEvent> inserted = deliveryStore.insertEventIfAbsent(command);
        if (inserted.isPresent()) {
            StoredEvent event = inserted.orElseThrow();
            List<RoutingEndpoint> routes = endpointRoutingQuery.findEnabledForExactEventType(
                    command.projectId(),
                    command.eventType()
            );
            List<PendingDelivery> deliveries = routes.stream()
                    .map(route -> new PendingDelivery(UUID.randomUUID(), route.endpointId()))
                    .toList();
            deliveryStore.insertOriginalDeliveries(command.projectId(), event.id(), deliveries);
            return result(event, deliveries.size(), false);
        }

        StoredEvent existing = deliveryStore.findEventByProjectAndIdempotencyKey(
                command.projectId(),
                command.idempotencyKey()
        ).orElseThrow(() -> new IllegalStateException("idempotency conflict event was not visible after insert"));
        if (!deliveryStore.eventHasEquivalentCommand(
                existing.id(),
                command.eventType(),
                payloadJson(command.payload())
        )) {
            throw new PublishIdempotencyConflictException();
        }
        return result(existing, deliveryStore.countOriginalDeliveries(existing.id()), true);
    }

    private JsonNode parsePayload(String payloadJson) {
        try {
            JsonNode payload = objectMapper.readTree(
                    Objects.requireNonNull(payloadJson, "payloadJson must not be null")
            );
            if (payload == null) {
                throw new IllegalArgumentException("payload must be a JSON value");
            }
            return payload;
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("payload must be valid JSON", exception);
        }
    }

    private String payloadJson(JsonNode payload) {
        try {
            return new String(objectMapper.writeValueAsBytes(payload), java.nio.charset.StandardCharsets.UTF_8);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("payload cannot be serialized as JSON", exception);
        }
    }

    private static PublishEventResult result(StoredEvent event, int deliveryCount, boolean idempotentReplay) {
        return new PublishEventResult(
                event.id(),
                event.projectId(),
                event.eventType(),
                event.acceptedAt(),
                deliveryCount,
                idempotentReplay
        );
    }
}
