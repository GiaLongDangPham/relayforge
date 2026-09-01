package com.gialong.relayforge.endpoint.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Entity(name = "WebhookEndpoint")
@Table(name = "webhook_endpoints", schema = "public")
public class WebhookEndpointEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "destination_url", nullable = false, length = 2048)
    private String destinationUrl;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "minimum_retry_delay_seconds")
    private Integer minimumRetryDelaySeconds;

    @Column(name = "signing_secret_ciphertext", nullable = false, updatable = false)
    private byte[] signingSecretCiphertext;

    @Column(name = "encryption_key_reference", nullable = false, length = 128, updatable = false)
    private String encryptionKeyReference;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected WebhookEndpointEntity() {
    }

    private WebhookEndpointEntity(
            UUID id,
            UUID projectId,
            String name,
            String destinationUrl,
            boolean enabled,
            Integer minimumRetryDelaySeconds,
            byte[] signingSecretCiphertext,
            String encryptionKeyReference
    ) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.projectId = Objects.requireNonNull(projectId, "projectId must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.destinationUrl = Objects.requireNonNull(destinationUrl, "destinationUrl must not be null");
        this.enabled = enabled;
        this.minimumRetryDelaySeconds = minimumRetryDelaySeconds;
        this.signingSecretCiphertext = Arrays.copyOf(
                Objects.requireNonNull(signingSecretCiphertext, "signingSecretCiphertext must not be null"),
                signingSecretCiphertext.length
        );
        this.encryptionKeyReference = Objects.requireNonNull(
                encryptionKeyReference,
                "encryptionKeyReference must not be null"
        );
    }

    public static WebhookEndpointEntity create(
            UUID id,
            UUID projectId,
            String name,
            String destinationUrl,
            boolean enabled,
            Integer minimumRetryDelaySeconds,
            byte[] signingSecretCiphertext,
            String encryptionKeyReference
    ) {
        return new WebhookEndpointEntity(
                id,
                projectId,
                name,
                destinationUrl,
                enabled,
                minimumRetryDelaySeconds,
                signingSecretCiphertext,
                encryptionKeyReference
        );
    }

    public void replaceConfiguration(String name, String destinationUrl) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.destinationUrl = Objects.requireNonNull(destinationUrl, "destinationUrl must not be null");
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public String name() {
        return name;
    }

    public String destinationUrl() {
        return destinationUrl;
    }

    public boolean enabled() {
        return enabled;
    }

    public Integer minimumRetryDelaySeconds() {
        return minimumRetryDelaySeconds;
    }

    public byte[] signingSecretCiphertext() {
        return Arrays.copyOf(signingSecretCiphertext, signingSecretCiphertext.length);
    }

    public String encryptionKeyReference() {
        return encryptionKeyReference;
    }

    public Long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
