package com.gialong.relayforge.identity.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class BCryptOwnerPasswordVerifierTests {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(
            BCryptOwnerPasswordHasher.BCRYPT_STRENGTH
    );
    private final BCryptOwnerPasswordVerifier verifier = new BCryptOwnerPasswordVerifier();

    @Test
    void matchesStoredHashButNeverAuthenticatesAgainstDummyHash() {
        char[] correctPassword = "correct-secret".toCharArray();
        String storedHash = encoder.encode("correct-secret");

        assertThat(verifier.matches(correctPassword, Optional.of(storedHash))).isTrue();
        assertThat(verifier.matches(correctPassword, Optional.empty())).isFalse();
        assertThat(verifier.matches("relayforge-user-not-found".toCharArray(), Optional.empty()))
                .isFalse();
    }
}
