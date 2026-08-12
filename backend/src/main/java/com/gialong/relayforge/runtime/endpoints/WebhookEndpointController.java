package com.gialong.relayforge.runtime.endpoints;

import com.gialong.relayforge.endpoint.api.CreatedWebhookEndpoint;
import com.gialong.relayforge.endpoint.api.WebhookEndpointCatalog;
import com.gialong.relayforge.endpoint.api.WebhookEndpointDetails;
import com.gialong.relayforge.endpoint.api.WebhookEndpointPage;
import com.gialong.relayforge.identity.api.VerifiedOwner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/endpoints")
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class WebhookEndpointController {

    private final WebhookEndpointCatalog endpointCatalog;

    WebhookEndpointController(WebhookEndpointCatalog endpointCatalog) {
        this.endpointCatalog = Objects.requireNonNull(endpointCatalog, "endpointCatalog must not be null");
    }

    @PostMapping
    ResponseEntity<CreateEndpointResponse> create(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestBody CreateEndpointRequest request
    ) {
        CreatedWebhookEndpoint created = endpointCatalog.create(
                ownerId(authentication),
                projectId,
                request.name(),
                request.destinationUrl(),
                request.eventTypes(),
                request.enabled()
        ).orElseThrow(EndpointNotFoundException::new);
        WebhookEndpointDetails endpoint = created.endpoint();
        URI location = URI.create("/api/v1/projects/" + projectId + "/endpoints/" + endpoint.id());
        return ResponseEntity.created(location).body(new CreateEndpointResponse(
                endpoint.id(),
                endpoint.projectId(),
                endpoint.name(),
                endpoint.destinationUrl(),
                endpoint.eventTypes(),
                endpoint.enabled(),
                endpoint.version(),
                created.signingSecret(),
                endpoint.createdAt(),
                endpoint.updatedAt()
        ));
    }

    @GetMapping
    WebhookEndpointPage list(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor
    ) {
        return endpointCatalog.listOwned(ownerId(authentication), projectId, limit, cursor)
                .orElseThrow(EndpointNotFoundException::new);
    }

    @GetMapping("/{endpointId}")
    WebhookEndpointDetails findOne(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId
    ) {
        return endpointCatalog.findOwned(ownerId(authentication), projectId, endpointId)
                .orElseThrow(EndpointNotFoundException::new);
    }

    @PutMapping("/{endpointId}")
    WebhookEndpointDetails replaceConfiguration(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId,
            @RequestBody ReplaceEndpointRequest request
    ) {
        return endpointCatalog.replaceConfiguration(
                ownerId(authentication),
                projectId,
                endpointId,
                request.name(),
                request.destinationUrl(),
                request.eventTypes(),
                request.version()
        ).orElseThrow(EndpointNotFoundException::new);
    }

    @PostMapping("/{endpointId}/enable")
    WebhookEndpointDetails enable(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId,
            @RequestBody EndpointStateRequest request
    ) {
        return setEnabled(authentication, projectId, endpointId, true, request.version());
    }

    @PostMapping("/{endpointId}/disable")
    WebhookEndpointDetails disable(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID endpointId,
            @RequestBody EndpointStateRequest request
    ) {
        return setEnabled(authentication, projectId, endpointId, false, request.version());
    }

    private WebhookEndpointDetails setEnabled(
            Authentication authentication,
            UUID projectId,
            UUID endpointId,
            boolean enabled,
            long version
    ) {
        return endpointCatalog.setEnabled(ownerId(authentication), projectId, endpointId, enabled, version)
                .orElseThrow(EndpointNotFoundException::new);
    }

    private static UUID ownerId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof VerifiedOwner owner)) {
            throw new IllegalStateException("authenticated owner principal is required");
        }
        return owner.ownerId();
    }

    record CreateEndpointRequest(
            String name,
            String destinationUrl,
            List<String> eventTypes,
            Boolean enabled
    ) {

        CreateEndpointRequest {
            if (enabled == null) {
                throw new IllegalArgumentException("enabled must not be null");
            }
        }
    }

    record ReplaceEndpointRequest(
            String name,
            String destinationUrl,
            List<String> eventTypes,
            Long version
    ) {

        ReplaceEndpointRequest {
            if (version == null) {
                throw new IllegalArgumentException("version must not be null");
            }
        }
    }

    record EndpointStateRequest(Long version) {

        EndpointStateRequest {
            if (version == null) {
                throw new IllegalArgumentException("version must not be null");
            }
        }
    }

    record CreateEndpointResponse(
            UUID id,
            UUID projectId,
            String name,
            String destinationUrl,
            List<String> eventTypes,
            boolean enabled,
            long version,
            String signingSecret,
        Instant createdAt,
        Instant updatedAt
    ) {

        @Override
        public String toString() {
            return "CreateEndpointResponse[id=" + id + ", projectId=" + projectId + ", name=" + name
                    + ", destinationUrl=" + destinationUrl + ", eventTypes=" + eventTypes + ", enabled=" + enabled
                    + ", version=" + version + ", signingSecret=<redacted>, createdAt=" + createdAt
                    + ", updatedAt=" + updatedAt + "]";
        }
    }
}
