package com.gialong.relayforge.project.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Entity(name = "ProjectApiKey")
@Table(name = "project_api_keys", schema = "public")
public class ProjectApiKeyEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "display_name", nullable = false, length = 120)
    private String displayName;

    @Column(name = "key_hint", nullable = false, length = 24, updatable = false)
    private String keyHint;

    @Column(name = "secret_digest", nullable = false, updatable = false)
    private byte[] secretDigest;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected ProjectApiKeyEntity() {
    }

    private ProjectApiKeyEntity(
            UUID id,
            UUID projectId,
            String displayName,
            String keyHint,
            byte[] secretDigest
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.displayName = Objects.requireNonNull(displayName, "displayName must not be null");
        this.keyHint = Objects.requireNonNull(keyHint, "keyHint must not be null");
        this.secretDigest = Arrays.copyOf(
                Objects.requireNonNull(secretDigest, "secretDigest must not be null"),
                secretDigest.length
        );
    }

    public static ProjectApiKeyEntity create(
            UUID id,
            UUID projectId,
            String displayName,
            String keyHint,
            byte[] secretDigest
    ) {
        return new ProjectApiKeyEntity(id, projectId, displayName, keyHint, secretDigest);
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public String displayName() {
        return displayName;
    }

    public String keyHint() {
        return keyHint;
    }

    public byte[] secretDigest() {
        return Arrays.copyOf(secretDigest, secretDigest.length);
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant revokedAt() {
        return revokedAt;
    }
}
