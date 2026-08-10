package com.gialong.relayforge.runtime.security;

import com.gialong.relayforge.identity.api.OwnerCredentialVerifier;
import com.gialong.relayforge.identity.api.VerifiedOwner;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class OwnerAuthenticationProvider implements AuthenticationProvider {

    private static final String INVALID_CREDENTIALS_MESSAGE = "Invalid owner credentials";

    private final OwnerCredentialVerifier credentialVerifier;

    public OwnerAuthenticationProvider(OwnerCredentialVerifier credentialVerifier) {
        this.credentialVerifier = Objects.requireNonNull(credentialVerifier, "credentialVerifier must not be null");
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        String loginName = authentication.getPrincipal() instanceof String value ? value : "";
        char[] passwordCopy = ownedPasswordCopy(authentication.getCredentials());
        try {
            Optional<VerifiedOwner> verified = credentialVerifier.verify(loginName, passwordCopy);
            VerifiedOwner owner = verified.orElseThrow(
                    () -> new BadCredentialsException(INVALID_CREDENTIALS_MESSAGE)
            );
            return UsernamePasswordAuthenticationToken.authenticated(owner, null, List.of());
        } finally {
            Arrays.fill(passwordCopy, '\0');
        }
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }

    private char[] ownedPasswordCopy(Object credentials) {
        if (credentials instanceof char[] password) {
            return Arrays.copyOf(password, password.length);
        }
        if (credentials instanceof CharSequence password) {
            return password.toString().toCharArray();
        }
        return new char[0];
    }
}
