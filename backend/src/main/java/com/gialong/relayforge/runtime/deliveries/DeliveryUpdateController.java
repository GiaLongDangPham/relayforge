package com.gialong.relayforge.runtime.deliveries;

import com.gialong.relayforge.identity.api.VerifiedOwner;
import com.gialong.relayforge.project.api.ProjectCatalog;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Objects;
import java.util.UUID;

/** API-mode owner adapter for best-effort delivery-change invalidation only. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class DeliveryUpdateController {

    private final ProjectCatalog projectCatalog;
    private final DeliveryUpdateSseRegistry registry;

    DeliveryUpdateController(ProjectCatalog projectCatalog, DeliveryUpdateSseRegistry registry) {
        this.projectCatalog = Objects.requireNonNull(projectCatalog, "projectCatalog must not be null");
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    @GetMapping(value = "/delivery-updates", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<SseEmitter> subscribe(Authentication authentication, @PathVariable UUID projectId) {
        UUID ownerId = ownerId(authentication);
        if (projectCatalog.findOwned(ownerId, projectId).isEmpty()) {
            throw new DeliveryHistoryNotFoundException();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no")
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(registry.open(projectId));
    }

    private static UUID ownerId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof VerifiedOwner owner)) {
            throw new IllegalStateException("authenticated owner principal is required");
        }
        return owner.ownerId();
    }
}
