package com.gialong.relayforge.delivery.persistence;

import com.gialong.relayforge.delivery.application.DeliveryUpdateNotifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/** PostgreSQL delivers this notification after, never before, the surrounding transaction commits. */
@Repository
class PostgresDeliveryUpdateNotifier implements DeliveryUpdateNotifier {

    public static final String CHANNEL = "relayforge_delivery_updates";

    private final JdbcTemplate jdbcTemplate;

    PostgresDeliveryUpdateNotifier(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publishCommittedDeliveryChange(UUID projectId, UUID deliveryId) {
        UUID requiredProjectId = Objects.requireNonNull(projectId, "projectId must not be null");
        UUID requiredDeliveryId = Objects.requireNonNull(deliveryId, "deliveryId must not be null");
        jdbcTemplate.query(
                "select pg_notify(?, ?)",
                resultSet -> { },
                CHANNEL,
                requiredProjectId + ":" + requiredDeliveryId
        );
    }
}
