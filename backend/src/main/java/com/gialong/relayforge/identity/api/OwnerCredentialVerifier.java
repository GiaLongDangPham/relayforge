package com.gialong.relayforge.identity.api;

import java.util.Optional;

public interface OwnerCredentialVerifier {

    Optional<VerifiedOwner> verify(String loginName, char[] plaintextPassword);
}
