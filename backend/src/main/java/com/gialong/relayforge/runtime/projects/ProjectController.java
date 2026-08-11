package com.gialong.relayforge.runtime.projects;

import com.gialong.relayforge.identity.api.VerifiedOwner;
import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import com.gialong.relayforge.project.api.ProjectPage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class ProjectController {

    private final ProjectCatalog projectCatalog;

    ProjectController(ProjectCatalog projectCatalog) {
        this.projectCatalog = Objects.requireNonNull(projectCatalog, "projectCatalog must not be null");
    }

    @PostMapping
    ResponseEntity<ProjectDetails> create(Authentication authentication, @RequestBody CreateProjectRequest request) {
        ProjectDetails created = projectCatalog.create(ownerId(authentication), request.name());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + created.id())).body(created);
    }

    @GetMapping
    ProjectPage list(
            Authentication authentication,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor
    ) {
        return projectCatalog.listOwned(ownerId(authentication), limit, cursor);
    }

    @GetMapping("/{projectId}")
    ProjectDetails findOne(Authentication authentication, @PathVariable UUID projectId) {
        return projectCatalog.findOwned(ownerId(authentication), projectId)
                .orElseThrow(ProjectNotFoundException::new);
    }

    @PatchMapping("/{projectId}")
    ProjectDetails rename(
            Authentication authentication,
            @PathVariable UUID projectId,
            @RequestBody RenameProjectRequest request
    ) {
        return projectCatalog.rename(ownerId(authentication), projectId, request.name(), request.version())
                .orElseThrow(ProjectNotFoundException::new);
    }

    private static UUID ownerId(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof VerifiedOwner owner)) {
            throw new IllegalStateException("authenticated owner principal is required");
        }
        return owner.ownerId();
    }

    record CreateProjectRequest(String name) {
    }

    record RenameProjectRequest(String name, Long version) {

        RenameProjectRequest {
            if (version == null) {
                throw new IllegalArgumentException("version must not be null");
            }
        }
    }
}
