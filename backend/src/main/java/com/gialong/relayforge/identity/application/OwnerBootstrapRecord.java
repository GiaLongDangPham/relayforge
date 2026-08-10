package com.gialong.relayforge.identity.application;

import java.util.Objects;
import java.util.UUID;

public record OwnerBootstrapRecord(UUID ownerId, boolean created) {

    public OwnerBootstrapRecord {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
    }
}
