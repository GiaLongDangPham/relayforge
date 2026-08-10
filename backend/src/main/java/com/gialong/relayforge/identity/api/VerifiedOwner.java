package com.gialong.relayforge.identity.api;

import java.util.Objects;
import java.util.UUID;

public record VerifiedOwner(UUID ownerId, String loginName) {

    public VerifiedOwner {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(loginName, "loginName must not be null");
    }
}
