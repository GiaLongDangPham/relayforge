package com.gialong.relayforge.identity.security;

import com.gialong.relayforge.identity.application.OwnerPasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.CharBuffer;

@Component
public class BCryptOwnerPasswordHasher implements OwnerPasswordHasher {

    static final int BCRYPT_STRENGTH = 12;

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(BCRYPT_STRENGTH);

    @Override
    public String hash(char[] plaintextPassword) {
        return encoder.encode(CharBuffer.wrap(plaintextPassword));
    }
}
