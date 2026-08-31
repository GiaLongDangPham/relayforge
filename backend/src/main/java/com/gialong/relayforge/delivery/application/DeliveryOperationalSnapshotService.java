package com.gialong.relayforge.delivery.application;
import com.gialong.relayforge.delivery.api.operations.DeliveryOperationalSnapshot;
import com.gialong.relayforge.delivery.api.operations.DeliveryOperationalSnapshotQuery;

import com.gialong.relayforge.delivery.api.operations.DeliveryOperationalSnapshot;
import com.gialong.relayforge.delivery.api.operations.DeliveryOperationalSnapshotQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * Keeps the operational aggregate inside delivery ownership instead of letting a runtime adapter
 * reach into delivery tables directly.
 */
@Service
final class DeliveryOperationalSnapshotService implements DeliveryOperationalSnapshotQuery {

    private final DeliveryStore deliveryStore;
    private final TransactionTemplate transaction;

    DeliveryOperationalSnapshotService(DeliveryStore deliveryStore, PlatformTransactionManager transactionManager) {
        this.deliveryStore = Objects.requireNonNull(deliveryStore, "deliveryStore must not be null");
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"));
        this.transaction.setReadOnly(true);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public DeliveryOperationalSnapshot currentSnapshot() {
        return Objects.requireNonNull(
                transaction.execute(status -> deliveryStore.currentOperationalSnapshot()),
                "operational snapshot transaction returned no result"
        );
    }
}
