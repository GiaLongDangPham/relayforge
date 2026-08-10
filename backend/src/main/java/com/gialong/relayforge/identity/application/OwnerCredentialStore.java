package com.gialong.relayforge.identity.application;

import java.util.Optional;

public interface OwnerCredentialStore {

    Optional<OwnerCredentialRecord> findByCanonicalLogin(String canonicalLogin);
}
