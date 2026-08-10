package com.gialong.relayforge.identity.application;

import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapOutcome;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
final class OwnerBootstrapService implements OwnerBootstrap {

    private static final int MAX_LOGIN_LENGTH = 100;
    private static final Pattern CANONICAL_LOGIN = Pattern.compile("[a-z0-9][a-z0-9._-]*");

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
        String canonicalLogin = canonicalize(loginName);
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

    private static String canonicalize(String loginName) {
        if (loginName == null) {
            throw new IllegalArgumentException("loginName must not be null");
        }

        String canonical = loginName.strip().toLowerCase(Locale.ROOT);
        if (canonical.length() > MAX_LOGIN_LENGTH || !CANONICAL_LOGIN.matcher(canonical).matches()) {
            throw new IllegalArgumentException("loginName must use the canonical owner login format");
        }
        return canonical;
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
