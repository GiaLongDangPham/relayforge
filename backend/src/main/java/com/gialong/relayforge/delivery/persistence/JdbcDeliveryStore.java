package com.gialong.relayforge.delivery.persistence;

import com.gialong.relayforge.delivery.application.DeliveryStore;
import com.gialong.relayforge.delivery.application.ClaimCandidate;
import com.gialong.relayforge.delivery.application.NewEvent;
import com.gialong.relayforge.delivery.application.PendingDelivery;
import com.gialong.relayforge.delivery.application.StoredEvent;
import com.gialong.relayforge.delivery.api.ClaimedDelivery;
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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ClaimCandidate> lockDuePendingForEnabledEndpoints(Collection<UUID> enabledEndpointIds, int capacity) {
        if (enabledEndpointIds.isEmpty()) {
            return List.of();
        }
        List<UUID> endpointIds = List.copyOf(enabledEndpointIds);
        String placeholders = String.join(", ", java.util.Collections.nCopies(endpointIds.size(), "?"));
        List<Object> parameters = new ArrayList<>(endpointIds);
        parameters.add(capacity);
        return jdbcTemplate.query(
                "select id, project_id, endpoint_id from public.deliveries "
                        + "where state = 'PENDING' and due_at <= CURRENT_TIMESTAMP and attempt_count < 5 "
                        + "and endpoint_id in (" + placeholders + ") "
                        + "order by due_at, id limit ? for update skip locked",
                this::mapClaimCandidate,
                parameters.toArray()
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ClaimedDelivery claim(ClaimCandidate candidate, UUID claimToken, Duration initialLease) {
        return jdbcTemplate.query(
                        "update public.deliveries set state = 'CLAIMED', due_at = null, claim_token = ?, "
                                + "lease_expires_at = CURRENT_TIMESTAMP + (? * interval '1 millisecond'), "
                                + "updated_at = CURRENT_TIMESTAMP "
                                + "where id = ? and project_id = ? and endpoint_id = ? and state = 'PENDING' "
                                + "returning id, project_id, endpoint_id, claim_token, lease_expires_at",
                        this::mapClaimedDelivery,
                        claimToken,
                        initialLease.toMillis(),
                        candidate.deliveryId(),
                        candidate.projectId(),
                        candidate.endpointId()
                )
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("locked candidate could not be claimed"));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int recoverExpiredPreAttemptClaims(int capacity) {
        return jdbcTemplate.query(
                "with expired as ("
                        + "select id, claim_token from public.deliveries "
                        + "where state = 'CLAIMED' and lease_expires_at <= CURRENT_TIMESTAMP "
                        + "order by lease_expires_at, id limit ? for update skip locked"
                        + ") "
                        + "update public.deliveries delivery set state = 'PENDING', due_at = CURRENT_TIMESTAMP, "
                        + "claim_token = null, lease_expires_at = null, updated_at = CURRENT_TIMESTAMP "
                        + "from expired where delivery.id = expired.id and delivery.claim_token = expired.claim_token "
                        + "returning delivery.id",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                capacity
        ).size();
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

    private ClaimCandidate mapClaimCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClaimCandidate(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("endpoint_id", UUID.class)
        );
    }

    private ClaimedDelivery mapClaimedDelivery(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClaimedDelivery(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("endpoint_id", UUID.class),
                resultSet.getObject("claim_token", UUID.class),
                resultSet.getObject("lease_expires_at", OffsetDateTime.class).toInstant()
        );
    }
}
