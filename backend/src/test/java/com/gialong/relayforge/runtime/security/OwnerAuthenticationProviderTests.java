package com.gialong.relayforge.runtime.security;

import com.gialong.relayforge.identity.api.OwnerCredentialVerifier;
import com.gialong.relayforge.identity.api.VerifiedOwner;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OwnerAuthenticationProviderTests {

    @Test
    void delegatesWithOwnedPasswordCopyAndReturnsHashFreeAuthenticatedPrincipal() {
        UUID ownerId = UUID.randomUUID();
        AtomicReference<char[]> verifierPassword = new AtomicReference<>();
        AtomicReference<VerifiedOwner> verifierResult = new AtomicReference<>();
        OwnerCredentialVerifier verifier = (loginName, password) -> {
            assertThat(loginName).isEqualTo(" Owner.Login ");
            verifierPassword.set(password);
            VerifiedOwner owner = new VerifiedOwner(ownerId, "owner.login");
            verifierResult.set(owner);
            return Optional.of(owner);
        };
        OwnerAuthenticationProvider provider = new OwnerAuthenticationProvider(verifier);
        char[] callerPassword = "correct-secret".toCharArray();

        Authentication authenticated = provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(" Owner.Login ", callerPassword)
        );

        assertThat(authenticated.isAuthenticated()).isTrue();
        assertThat(authenticated.getPrincipal())
                .isSameAs(verifierResult.get());
        assertThat(authenticated.getCredentials()).isNull();
        assertThat(authenticated.getAuthorities()).isEmpty();
        assertThat(callerPassword).containsExactly("correct-secret".toCharArray());
        assertThat(verifierPassword.get()).containsOnly('\0');
    }

    @Test
    void mapsEveryEmptyVerificationToGenericBadCredentials() {
        OwnerCredentialVerifier verifier = (loginName, password) -> Optional.empty();
        OwnerAuthenticationProvider provider = new OwnerAuthenticationProvider(verifier);

        assertThatThrownBy(() -> provider.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("unknown.owner", "wrong-secret")
        ))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Invalid owner credentials");
    }

    @Test
    void supportsOnlyUsernamePasswordAuthenticationTokens() {
        OwnerAuthenticationProvider provider = new OwnerAuthenticationProvider((loginName, password) -> Optional.empty());

        assertThat(provider.supports(UsernamePasswordAuthenticationToken.class)).isTrue();
        assertThat(provider.supports(Authentication.class)).isFalse();
    }
}
