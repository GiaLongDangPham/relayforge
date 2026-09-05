package com.gialong.relayforge.runtime.deliveries;
import com.gialong.relayforge.delivery.api.history.AttemptHistoryDetails;
import com.gialong.relayforge.delivery.api.history.AttemptHistorySummary;
import com.gialong.relayforge.delivery.api.history.DeliveryDisplayStatus;
import com.gialong.relayforge.delivery.api.history.DeliveryHistory;
import com.gialong.relayforge.delivery.api.history.DeliveryHistoryDetails;
import com.gialong.relayforge.delivery.api.history.DeliveryHistoryPage;
import com.gialong.relayforge.delivery.api.history.EventHistoryDetails;
import com.gialong.relayforge.delivery.api.history.EventHistoryPage;
import com.gialong.relayforge.delivery.api.replay.DeliveryReplayer;
import com.gialong.relayforge.delivery.api.replay.ReplayDeliveryResult;

import com.gialong.relayforge.delivery.api.history.AttemptHistoryDetails;
import com.gialong.relayforge.delivery.api.history.AttemptHistorySummary;
import com.gialong.relayforge.delivery.api.history.DeliveryDisplayStatus;
import com.gialong.relayforge.delivery.api.history.DeliveryHistory;
import com.gialong.relayforge.delivery.api.history.DeliveryHistoryDetails;
import com.gialong.relayforge.delivery.api.history.DeliveryHistoryPage;
import com.gialong.relayforge.delivery.api.history.DeliveryProjectHealth;
import com.gialong.relayforge.delivery.api.replay.DeliveryReplayer;
import com.gialong.relayforge.delivery.api.history.EventHistoryDetails;
import com.gialong.relayforge.delivery.api.history.EventHistoryPage;
import com.gialong.relayforge.delivery.api.replay.ReplayDeliveryResult;
import com.gialong.relayforge.identity.api.VerifiedOwner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** API-mode owner adapter for delivery inspection and durable replay requests. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class DeliveryHistoryController {

    private final DeliveryHistory deliveryHistory;
    private final DeliveryReplayer deliveryReplayer;
    private final ObjectMapper objectMapper;

    DeliveryHistoryController(
            DeliveryHistory deliveryHistory,
            DeliveryReplayer deliveryReplayer,
            ObjectMapper objectMapper
    ) {
        this.deliveryHistory = Objects.requireNonNull(deliveryHistory, "deliveryHistory must not be null");
        this.deliveryReplayer = Objects.requireNonNull(deliveryReplayer, "deliveryReplayer must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @GetMapping("/delivery-health")
    ResponseEntity<DeliveryProjectHealth> findProjectHealth(
            Authentication authentication,
            @PathVariable UUID projectId
    ) {
        DeliveryProjectHealth health = deliveryHistory.findProjectHealth(ownerId(authentication), projectId)
                .orElseThrow(DeliveryHistoryNotFoundException::new);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(health);
    }

    @GetMapping("/events")
    EventHistoryPage listEvents(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String eventType,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor
    ) {
        return deliveryHistory.listEvents(ownerId(authentication), projectId, eventType, limit, cursor)
                .orElseThrow(DeliveryHistoryNotFoundException::new);
    }

    @GetMapping("/events/{eventId}")
    EventHistoryResponse findEvent(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID eventId
    ) {
        EventHistoryDetails event = deliveryHistory.findEvent(ownerId(authentication), projectId, eventId)
                .orElseThrow(DeliveryHistoryNotFoundException::new);
        return new EventHistoryResponse(event.event(), parsePayload(event.payloadJson()), event.deliverySummary());
    }

    @GetMapping("/events/{eventId}/deliveries")
    DeliveryHistoryPage listEventDeliveries(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID eventId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor
    ) {
        return deliveryHistory.listEventDeliveries(ownerId(authentication), projectId, eventId, limit, cursor)
                .orElseThrow(DeliveryHistoryNotFoundException::new);
    }

    @GetMapping("/deliveries")
    DeliveryHistoryPage listDeliveries(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID eventId,
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) DeliveryDisplayStatus status,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor
    ) {
        return deliveryHistory.listDeliveries(
                ownerId(authentication),
                projectId,
                eventId,
                endpointId,
                status,
                limit,
                cursor
        ).orElseThrow(DeliveryHistoryNotFoundException::new);
    }

    @GetMapping("/deliveries/{deliveryId}")
    DeliveryHistoryDetails findDelivery(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID deliveryId
    ) {
        return deliveryHistory.findDelivery(ownerId(authentication), projectId, deliveryId)
                .orElseThrow(DeliveryHistoryNotFoundException::new);
    }

    @GetMapping("/deliveries/{deliveryId}/attempts")
    List<AttemptHistorySummary> listAttempts(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID deliveryId
    ) {
        return deliveryHistory.listAttempts(ownerId(authentication), projectId, deliveryId)
                .orElseThrow(DeliveryHistoryNotFoundException::new);
    }

    @GetMapping("/deliveries/{deliveryId}/attempts/{attemptId}")
    AttemptHistoryDetails findAttempt(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID deliveryId,
            @PathVariable UUID attemptId
    ) {
        return deliveryHistory.findAttempt(ownerId(authentication), projectId, deliveryId, attemptId)
                .orElseThrow(DeliveryHistoryNotFoundException::new);
    }

    @PostMapping("/deliveries/{deliveryId}/replays")
    ResponseEntity<ReplayDeliveryResult> replay(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID deliveryId,
            @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        ReplayDeliveryResult replay = deliveryReplayer.replay(
                ownerId(authentication),
                projectId,
                deliveryId,
                idempotencyKey
        ).orElseThrow(DeliveryHistoryNotFoundException::new);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(replay);
    }

    private JsonNode parsePayload(String payloadJson) {
        try {
            JsonNode payload = objectMapper.readTree(payloadJson);
            if (payload == null) {
                throw new IllegalStateException("stored event payload is unexpectedly null");
            }
            return payload;
        } catch (JacksonException exception) {
            throw new IllegalStateException("stored event payload is invalid JSON", exception);
        }
    }

    private static UUID ownerId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof VerifiedOwner owner)) {
            throw new IllegalStateException("authenticated owner principal is required");
        }
        return owner.ownerId();
    }

    record EventHistoryResponse(
            com.gialong.relayforge.delivery.api.history.EventHistorySummary event,
            JsonNode payload,
            com.gialong.relayforge.delivery.api.history.EventDeliverySummary deliverySummary
    ) {
    }
}
