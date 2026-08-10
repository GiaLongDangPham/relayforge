package com.gialong.relayforge.identity.application;

import com.gialong.relayforge.identity.api.OwnerCredentialVerifier;
import com.gialong.relayforge.identity.api.OwnerLoginNames;
import com.gialong.relayforge.identity.api.VerifiedOwner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

@Service
final class OwnerCredentialVerificationService implements OwnerCredentialVerifier {

    private final OwnerCredentialStore credentialStore;
    private final OwnerPasswordVerifier passwordVerifier;
    private final TransactionTemplate readTransaction;

    OwnerCredentialVerificationService(
            OwnerCredentialStore credentialStore,
            OwnerPasswordVerifier passwordVerifier,
            PlatformTransactionManager transactionManager
    ) {
        this.credentialStore = credentialStore;
        this.passwordVerifier = passwordVerifier;
        this.readTransaction = new TransactionTemplate(transactionManager);
        this.readTransaction.setReadOnly(true);
    }

    @Override
    public Optional<VerifiedOwner> verify(String loginName, char[] plaintextPassword) {
        Optional<String> canonicalLogin = OwnerLoginNames.canonicalize(loginName);
        Optional<OwnerCredentialRecord> storedCredential = canonicalLogin.flatMap(login ->
                Objects.requireNonNull(
                        readTransaction.execute(status -> credentialStore.findByCanonicalLogin(login)),
                        "credential read transaction returned no result"
                )
        );

        char[] passwordCopy = plaintextPassword == null
                ? new char[0]
                : Arrays.copyOf(plaintextPassword, plaintextPassword.length);
        try {
            boolean passwordMatches = passwordVerifier.matches(
                    passwordCopy,
                    storedCredential.map(OwnerCredentialRecord::passwordHash)
            );
            if (!passwordMatches || storedCredential.isEmpty()) {
                return Optional.empty();
            }

            OwnerCredentialRecord verified = storedCredential.orElseThrow();
            return Optional.of(new VerifiedOwner(verified.ownerId(), verified.loginName()));
        } finally {
            Arrays.fill(passwordCopy, '\0');
        }
    }
}
