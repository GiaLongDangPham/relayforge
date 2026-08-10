package com.gialong.relayforge.identity.security;

import com.gialong.relayforge.identity.application.OwnerPasswordVerifier;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.CharBuffer;
import java.util.Objects;
import java.util.Optional;

@Component
public class BCryptOwnerPasswordVerifier implements OwnerPasswordVerifier {

    private static final String DUMMY_PASSWORD = "relayforge-user-not-found";

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(
            BCryptOwnerPasswordHasher.BCRYPT_STRENGTH
    );
    private final String dummyPasswordHash = encoder.encode(DUMMY_PASSWORD);

    @Override
    public boolean matches(char[] plaintextPassword, Optional<String> storedPasswordHash) {
        Objects.requireNonNull(plaintextPassword, "plaintextPassword must not be null");
        Objects.requireNonNull(storedPasswordHash, "storedPasswordHash must not be null");

        String hashToCheck = storedPasswordHash.orElse(dummyPasswordHash);
        boolean matches = encoder.matches(CharBuffer.wrap(plaintextPassword), hashToCheck);
        return storedPasswordHash.isPresent() && matches;
    }
}
