package com.gialong.relayforge.identity.application;

import com.gialong.relayforge.identity.api.VerifiedOwner;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerCredentialVerificationServiceTests {

    @Test
    void readsInShortTransactionThenVerifiesCopyOutsideAndClearsIt() {
        UUID ownerId = UUID.randomUUID();
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        AtomicReference<char[]> verifiedPasswordReference = new AtomicReference<>();
        OwnerCredentialStore credentialStore = canonicalLogin -> {
            assertThat(transactionManager.active()).isTrue();
            assertThat(transactionManager.readOnly()).isTrue();
            assertThat(canonicalLogin).isEqualTo("verified.owner");
            return Optional.of(new OwnerCredentialRecord(
                    ownerId,
                    canonicalLogin,
                    "$2a$12$stored-test-hash"
            ));
        };
        OwnerPasswordVerifier passwordVerifier = (plaintextPassword, storedPasswordHash) -> {
            assertThat(transactionManager.active()).isFalse();
            assertThat(storedPasswordHash).contains("$2a$12$stored-test-hash");
            verifiedPasswordReference.set(plaintextPassword);
            return true;
        };
        OwnerCredentialVerificationService service = new OwnerCredentialVerificationService(
                credentialStore,
                passwordVerifier,
                transactionManager
        );
        char[] callerPassword = "verification-secret".toCharArray();

        Optional<VerifiedOwner> result = service.verify(" Verified.Owner ", callerPassword);

        assertThat(result).contains(new VerifiedOwner(ownerId, "verified.owner"));
        assertThat(callerPassword).containsExactly("verification-secret".toCharArray());
        assertThat(verifiedPasswordReference.get()).containsOnly('\0');
        assertThat(transactionManager.transactionCount()).isEqualTo(1);
    }

    @Test
    void wrongPasswordAndUnknownLoginReturnSameEmptyOutcome() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        OwnerCredentialRecord stored = new OwnerCredentialRecord(
                UUID.randomUUID(),
                "known.owner",
                "$2a$12$known-test-hash"
        );
        OwnerCredentialStore credentialStore = canonicalLogin -> canonicalLogin.equals("known.owner")
                ? Optional.of(stored)
                : Optional.empty();
        AtomicInteger verificationCount = new AtomicInteger();
        OwnerPasswordVerifier passwordVerifier = (plaintextPassword, storedPasswordHash) -> {
            verificationCount.incrementAndGet();
            return false;
        };
        OwnerCredentialVerificationService service = new OwnerCredentialVerificationService(
                credentialStore,
                passwordVerifier,
                transactionManager
        );

        Optional<VerifiedOwner> wrongPassword = service.verify(
                "known.owner",
                "wrong-secret".toCharArray()
        );
        Optional<VerifiedOwner> unknownLogin = service.verify(
                "unknown.owner",
                "wrong-secret".toCharArray()
        );

        assertThat(wrongPassword).isEmpty();
        assertThat(unknownLogin).isEmpty();
        assertThat(verificationCount).hasValue(2);
        assertThat(transactionManager.transactionCount()).isEqualTo(2);
    }

    @Test
    void malformedLoginAndNullPasswordStillPerformDummyVerificationWithoutQuery() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        AtomicInteger queryCount = new AtomicInteger();
        OwnerCredentialStore credentialStore = canonicalLogin -> {
            queryCount.incrementAndGet();
            return Optional.empty();
        };
        AtomicReference<char[]> verifiedPasswordReference = new AtomicReference<>();
        AtomicReference<Optional<String>> verifiedHash = new AtomicReference<>();
        OwnerPasswordVerifier passwordVerifier = (plaintextPassword, storedPasswordHash) -> {
            verifiedPasswordReference.set(plaintextPassword);
            verifiedHash.set(storedPasswordHash);
            return false;
        };
        OwnerCredentialVerificationService service = new OwnerCredentialVerificationService(
                credentialStore,
                passwordVerifier,
                transactionManager
        );

        Optional<VerifiedOwner> result = service.verify("invalid@owner", null);

        assertThat(result).isEmpty();
        assertThat(queryCount).hasValue(0);
        assertThat(transactionManager.transactionCount()).isZero();
        assertThat(verifiedHash.get()).isEmpty();
        assertThat(verifiedPasswordReference.get()).isEmpty();
    }

    @Test
    void internalCredentialProjectionDoesNotRenderPasswordHash() {
        String passwordHashMarker = "$2a$12$sensitive-hash-marker";
        OwnerCredentialRecord credential = new OwnerCredentialRecord(
                UUID.randomUUID(),
                "safe.owner",
                passwordHashMarker
        );

        assertThat(credential.toString()).doesNotContain(passwordHashMarker);
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private boolean active;
        private boolean readOnly;
        private int transactionCount;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            active = true;
            readOnly = definition.isReadOnly();
            transactionCount++;
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
            active = false;
        }

        @Override
        public void rollback(TransactionStatus status) {
            active = false;
        }

        boolean active() {
            return active;
        }

        boolean readOnly() {
            return readOnly;
        }

        int transactionCount() {
            return transactionCount;
        }
    }
}
