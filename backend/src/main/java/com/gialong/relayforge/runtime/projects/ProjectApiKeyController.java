package com.gialong.relayforge.runtime.projects;

import com.gialong.relayforge.identity.api.VerifiedOwner;
import com.gialong.relayforge.project.api.CreatedProjectApiKey;
import com.gialong.relayforge.project.api.ProjectApiKeyCatalog;
import com.gialong.relayforge.project.api.ProjectApiKeyDetails;
import com.gialong.relayforge.project.api.ProjectApiKeyPage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/api-keys")
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class ProjectApiKeyController {

    private final ProjectApiKeyCatalog apiKeyCatalog;

    ProjectApiKeyController(ProjectApiKeyCatalog apiKeyCatalog) {
        this.apiKeyCatalog = Objects.requireNonNull(apiKeyCatalog, "apiKeyCatalog must not be null");
    }

    @PostMapping
    ResponseEntity<CreateProjectApiKeyResponse> create(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestBody CreateProjectApiKeyRequest request
    ) {
        CreatedProjectApiKey created = apiKeyCatalog.create(ownerId(authentication), projectId, request.displayName())
                .orElseThrow(ProjectNotFoundException::new);
        ProjectApiKeyDetails details = created.apiKey();
        CreateProjectApiKeyResponse response = new CreateProjectApiKeyResponse(
                details.id(),
                details.displayName(),
                details.keyHint(),
                created.rawKey(),
                details.createdAt(),
                details.revokedAt()
        );
        URI location = URI.create("/api/v1/projects/" + projectId + "/api-keys/" + details.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    ProjectApiKeyPage list(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor
    ) {
        return apiKeyCatalog.listOwned(ownerId(authentication), projectId, limit, cursor)
                .orElseThrow(ProjectNotFoundException::new);
    }

    @PostMapping("/{apiKeyId}/revoke")
    ProjectApiKeyDetails revoke(
            Authentication authentication,
            @PathVariable UUID projectId,
            @PathVariable UUID apiKeyId
    ) {
        return apiKeyCatalog.revoke(ownerId(authentication), projectId, apiKeyId)
                .orElseThrow(ProjectNotFoundException::new);
    }

    private static UUID ownerId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof VerifiedOwner owner)) {
            throw new IllegalStateException("authenticated owner principal is required");
        }
        return owner.ownerId();
    }

    record CreateProjectApiKeyRequest(String displayName) {
    }

    record CreateProjectApiKeyResponse(
            UUID id,
            String displayName,
            String keyHint,
            String rawKey,
            Instant createdAt,
            Instant revokedAt
    ) {
    }
}
