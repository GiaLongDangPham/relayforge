package com.gialong.relayforge.identity.application;

import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapOutcome;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.identity.api.OwnerLoginNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Service
final class OwnerBootstrapService implements OwnerBootstrap {

    private final OwnerPasswordHasher passwordHasher;
    private final OwnerBootstrapStore ownerStore;
    private final TransactionTemplate transactionTemplate;

    OwnerBootstrapService(
            OwnerPasswordHasher passwordHasher,
            OwnerBootstrapStore ownerStore,
            PlatformTransactionManager transactionManager
    ) {
        this.passwordHasher = passwordHasher;
        this.ownerStore = ownerStore;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public OwnerBootstrapResult bootstrap(String loginName, char[] plaintextPassword) {
        String canonicalLogin = OwnerLoginNames.requireCanonical(loginName);
        validatePassword(plaintextPassword);

        char[] passwordCopy = Arrays.copyOf(plaintextPassword, plaintextPassword.length);
        String passwordHash;
        try {
            passwordHash = passwordHasher.hash(passwordCopy);
        } finally {
            Arrays.fill(passwordCopy, '\0');
        }

        UUID candidateId = UUID.randomUUID();
        OwnerBootstrapRecord stored = Objects.requireNonNull(
                transactionTemplate.execute(status -> ownerStore.insertOrGet(
                        candidateId,
                        canonicalLogin,
                        passwordHash
                )),
                "bootstrap transaction returned no result"
        );

        OwnerBootstrapOutcome outcome = stored.created()
                ? OwnerBootstrapOutcome.CREATED
                : OwnerBootstrapOutcome.EXISTING;
        return new OwnerBootstrapResult(stored.ownerId(), canonicalLogin, outcome);
    }

    private static void validatePassword(char[] plaintextPassword) {
        if (plaintextPassword == null || plaintextPassword.length == 0) {
            throw new IllegalArgumentException("plaintextPassword must not be empty");
        }

        boolean containsNonWhitespace = false;
        for (char character : plaintextPassword) {
            if (!Character.isWhitespace(character)) {
                containsNonWhitespace = true;
                break;
            }
        }
        if (!containsNonWhitespace) {
            throw new IllegalArgumentException("plaintextPassword must not be blank");
        }
    }
}
