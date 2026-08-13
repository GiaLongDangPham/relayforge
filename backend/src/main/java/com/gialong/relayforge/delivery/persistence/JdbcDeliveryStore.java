package com.gialong.relayforge.delivery.persistence;

import com.gialong.relayforge.delivery.application.DeliveryStore;
import com.gialong.relayforge.delivery.application.NewEvent;
import com.gialong.relayforge.delivery.application.PendingDelivery;
import com.gialong.relayforge.delivery.application.StoredEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL JSONB and conflict SQL keep publish idempotency explicit without a repository abstraction.
 */
@Repository
@RequiredArgsConstructor
public class JdbcDeliveryStore implements DeliveryStore {

    private static final String EVENT_COLUMNS = "id, project_id, event_type, accepted_at";
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StoredEvent> insertEventIfAbsent(NewEvent event) {
        return jdbcTemplate.query(
                        "insert into public.events (id, project_id, event_type, payload, idempotency_key, "
                                + "fingerprint_version, command_fingerprint) "
                                + "values (?, ?, ?, ?::jsonb, ?, ?, ?) "
                                + "on conflict (project_id, idempotency_key) do nothing "
                                + "returning " + EVENT_COLUMNS,
                        this::mapEvent,
                        event.id(),
                        event.projectId(),
                        event.eventType(),
                        payloadJson(event.payload()),
                        event.idempotencyKey(),
                        event.fingerprintVersion(),
                        event.commandFingerprint()
                )
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<StoredEvent> findEventByProjectAndIdempotencyKey(UUID projectId, String idempotencyKey) {
        return jdbcTemplate.query(
                        "select " + EVENT_COLUMNS + " from public.events "
                                + "where project_id = ? and idempotency_key = ?",
                        this::mapEvent,
                        projectId,
                        idempotencyKey
                )
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean eventHasEquivalentCommand(UUID eventId, String eventType, String payloadJson) {
        Boolean equivalent = jdbcTemplate.queryForObject(
                "select exists(select 1 from public.events where id = ? and event_type = ? and payload = ?::jsonb)",
                Boolean.class,
                eventId,
                eventType,
                payloadJson
        );
        return Boolean.TRUE.equals(equivalent);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertOriginalDeliveries(UUID projectId, UUID eventId, List<PendingDelivery> deliveries) {
        for (PendingDelivery delivery : deliveries) {
            jdbcTemplate.update(
                    "insert into public.deliveries (id, project_id, event_id, endpoint_id, state, due_at, attempt_count) "
                            + "values (?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP, 0)",
                    delivery.id(),
                    projectId,
                    eventId,
                    delivery.endpointId()
            );
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public int countOriginalDeliveries(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from public.deliveries where event_id = ?",
                Integer.class,
                eventId
        );
        return count == null ? 0 : count;
    }

    private String payloadJson(JsonNode payload) {
        try {
            return new String(objectMapper.writeValueAsBytes(payload), StandardCharsets.UTF_8);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("payload cannot be serialized as JSON", exception);
        }
    }

    private StoredEvent mapEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredEvent(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getObject("accepted_at", OffsetDateTime.class).toInstant()
        );
    }
}
