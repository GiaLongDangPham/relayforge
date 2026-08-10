package com.gialong.relayforge.identity.api;

import java.util.Objects;
import java.util.UUID;

public record OwnerBootstrapResult(
        UUID ownerId,
        String loginName,
        OwnerBootstrapOutcome outcome
) {

    public OwnerBootstrapResult {
        Objects.requireNonNull(ownerId, "ownerId must not be null");
        Objects.requireNonNull(loginName, "loginName must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
