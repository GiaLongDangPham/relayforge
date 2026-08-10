package com.gialong.relayforge.identity.application;

import java.util.Optional;

public interface OwnerPasswordVerifier {

    boolean matches(char[] plaintextPassword, Optional<String> storedPasswordHash);
}
