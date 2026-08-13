package com.gialong.relayforge.runtime.publisher;

import com.gialong.relayforge.delivery.api.EventPublisher;
import com.gialong.relayforge.delivery.api.PublishEventResult;
import com.gialong.relayforge.project.api.VerifiedPublisherProject;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/events")
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class PublisherEventController {

    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final Set<String> ALLOWED_REQUEST_FIELDS = Set.of("eventType", "payload");

    private final EventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    PublisherEventController(EventPublisher eventPublisher, ObjectMapper objectMapper) {
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
    }

    @PostMapping(consumes = "application/json")
    ResponseEntity<PublishEventResponse> publish(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            HttpServletRequest request
    ) throws IOException {
        VerifiedPublisherProject publisher = verifiedPublisher(authentication);
        if (!publisher.projectId().equals(projectId)) {
            throw new PublisherProjectForbiddenException();
        }
        ParsedPublishRequest parsed = parse(readBounded(request));
        PublishEventResult accepted = eventPublisher.publish(
                projectId,
                idempotencyKey,
                parsed.eventType(),
                serializePayload(parsed.payload())
        );
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new PublishEventResponse(
                accepted.eventId(),
                accepted.projectId(),
                accepted.eventType(),
                accepted.acceptedAt(),
                accepted.deliveryCount(),
                accepted.idempotentReplay()
        ));
    }

    private ParsedPublishRequest parse(byte[] body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("publish request must be a JSON object");
            }
            for (var property : root.properties()) {
                if (!ALLOWED_REQUEST_FIELDS.contains(property.getKey())) {
                    throw new IllegalArgumentException("publish request contains an unknown field");
                }
            }
            JsonNode eventType = root.get("eventType");
            JsonNode payload = root.get("payload");
            if (eventType == null || !eventType.isTextual() || payload == null) {
                throw new IllegalArgumentException("publish request requires textual eventType and payload");
            }
            return new ParsedPublishRequest(eventType.asText(), payload);
        } catch (JacksonException exception) {
            throw new PublisherMalformedJsonException(exception);
        } finally {
            java.util.Arrays.fill(body, (byte) 0);
        }
    }

    private String serializePayload(JsonNode payload) {
        try {
            return new String(objectMapper.writeValueAsBytes(payload), java.nio.charset.StandardCharsets.UTF_8);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("publish payload cannot be serialized", exception);
        }
    }

    private static byte[] readBounded(HttpServletRequest request) throws IOException {
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_REQUEST_BYTES) {
            throw new PublisherRequestTooLargeException();
        }
        try (InputStream input = request.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (output.size() + read > MAX_REQUEST_BYTES) {
                    throw new PublisherRequestTooLargeException();
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static VerifiedPublisherProject verifiedPublisher(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof VerifiedPublisherProject publisher)) {
            throw new IllegalStateException("verified publisher principal is required");
        }
        return publisher;
    }

    private record ParsedPublishRequest(String eventType, JsonNode payload) {
    }

    record PublishEventResponse(
            UUID eventId,
            UUID projectId,
            String eventType,
            Instant acceptedAt,
            int deliveryCount,
            boolean idempotentReplay
    ) {
    }
}
