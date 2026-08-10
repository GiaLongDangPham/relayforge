package com.gialong.relayforge.identity.api;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public record VerifiedOwner(UUID ownerId, String loginName) implements Serializable {

    private static final long serialVersionUID = 1L;

    public VerifiedOwner {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(loginName, "loginName must not be null");
    }
}
