package com.gialong.relayforge.identity.application;

import java.util.Objects;
import java.util.UUID;

public final class OwnerCredentialRecord {

    private final UUID ownerId;
    private final String loginName;
    private final String passwordHash;

    public OwnerCredentialRecord(UUID ownerId, String loginName, String passwordHash) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId must not be null");
        this.loginName = Objects.requireNonNull(loginName, "loginName must not be null");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash must not be null");
    }

    public UUID ownerId() {
        return ownerId;
    }

    public String loginName() {
        return loginName;
    }

    public String passwordHash() {
        return passwordHash;
    }
}
