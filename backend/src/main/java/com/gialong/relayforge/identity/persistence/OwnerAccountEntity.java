package com.gialong.relayforge.identity.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.SourceType;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity(name = "OwnerAccount")
@Table(name = "owner_accounts", schema = "public")
public class OwnerAccountEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "login_name", nullable = false, length = 100, unique = true)
    private String loginName;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreationTimestamp(source = SourceType.DB)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp(source = SourceType.DB)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OwnerAccountEntity() {
    }

    private OwnerAccountEntity(UUID id, String loginName, String passwordHash) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.loginName = Objects.requireNonNull(loginName, "loginName must not be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
    }

    public static OwnerAccountEntity create(UUID id, String loginName, String passwordHash) {
        return new OwnerAccountEntity(id, loginName, passwordHash);
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
    }

    public UUID id() {
        return id;
    }

    public String loginName() {
        return loginName;
    }

    public String passwordHash() {
        return passwordHash;
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
