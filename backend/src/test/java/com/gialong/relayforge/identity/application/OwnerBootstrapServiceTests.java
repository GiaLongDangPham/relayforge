package com.gialong.relayforge.identity.application;

import com.gialong.relayforge.identity.api.OwnerBootstrapOutcome;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerBootstrapServiceTests {

    @Test
    void hashesBeforeReadCommittedTransactionAndClearsTemporaryCopy() {
        RecordingTransactionManager transactionManager = new RecordingTransactionManager();
        AtomicReference<char[]> hashedPasswordReference = new AtomicReference<>();
        OwnerPasswordHasher passwordHasher = password -> {
            assertThat(transactionManager.active()).isFalse();
            hashedPasswordReference.set(password);
            return "$2a$12$unit-test-hash";
        };
        OwnerBootstrapStore ownerStore = (candidateId, loginName, passwordHash) -> {
            assertThat(transactionManager.active()).isTrue();
            assertThat(transactionManager.isolationLevel())
                    .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
            assertThat(loginName).isEqualTo("unit.owner");
            assertThat(passwordHash).isEqualTo("$2a$12$unit-test-hash");
            return new OwnerBootstrapRecord(candidateId, true);
        };
        OwnerBootstrapService service = new OwnerBootstrapService(
                passwordHasher,
                ownerStore,
                transactionManager
        );
        char[] callerPassword = "unit-secret".toCharArray();

        OwnerBootstrapResult result = service.bootstrap(" Unit.Owner ", callerPassword);

        assertThat(result.loginName()).isEqualTo("unit.owner");
        assertThat(result.outcome()).isEqualTo(OwnerBootstrapOutcome.CREATED);
        assertThat(callerPassword).containsExactly("unit-secret".toCharArray());
        assertThat(hashedPasswordReference.get()).containsOnly('\0');
    }

    private static final class RecordingTransactionManager implements PlatformTransactionManager {

        private boolean active;
        private int isolationLevel;

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            active = true;
            isolationLevel = definition.getIsolationLevel();
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

        int isolationLevel() {
            return isolationLevel;
        }
    }
}
