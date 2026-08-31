package com.gialong.relayforge.delivery.persistence;

import com.gialong.relayforge.delivery.application.DeliveryStore;
import com.gialong.relayforge.delivery.application.ClaimCandidate;
import com.gialong.relayforge.delivery.application.AttemptStartCandidate;
import com.gialong.relayforge.delivery.application.AttemptCompletion;
import com.gialong.relayforge.delivery.application.CompletionDecision;
import com.gialong.relayforge.delivery.application.ExpiredStartedAttempt;
import com.gialong.relayforge.delivery.application.NewEvent;
import com.gialong.relayforge.delivery.application.PendingDelivery;
import com.gialong.relayforge.delivery.application.StartedAttempt;
import com.gialong.relayforge.delivery.application.StoredEvent;
import com.gialong.relayforge.delivery.application.EventHistoryCursor;
import com.gialong.relayforge.delivery.application.DeliveryHistoryCursor;
import com.gialong.relayforge.delivery.application.HistoryRecords;
import com.gialong.relayforge.delivery.api.ClaimedDelivery;
import com.gialong.relayforge.delivery.api.DispatchInstruction;
import com.gialong.relayforge.delivery.api.AttemptHistoryStatus;
import com.gialong.relayforge.delivery.api.AttemptHistorySummary;
import com.gialong.relayforge.delivery.api.DeliveryDisplayStatus;
import com.gialong.relayforge.delivery.api.DeliveryStoredState;
import com.gialong.relayforge.delivery.api.EventDeliverySummary;
import com.gialong.relayforge.delivery.api.DeliveryOperationalSnapshot;
import com.gialong.relayforge.delivery.api.ReplayDeliveryResult;
import com.gialong.relayforge.delivery.api.RetentionCleanupResult;
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
    private static final String HISTORY_DELIVERY_COLUMNS = "delivery.id, delivery.event_id, delivery.endpoint_id, "
            + "delivery.replay_of_delivery_id, delivery.state, delivery.attempt_count, delivery.due_at, "
            + "(delivery.state = 'PENDING' and delivery.attempt_count > 0 and delivery.due_at > CURRENT_TIMESTAMP) "
            + "as retry_scheduled, delivery.created_at, delivery.terminal_at";
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
                fairClaimCandidateSql(placeholders),
                this::mapClaimCandidate,
                parameters.toArray()
        );
    }

    /**
     * Rank due rows within an endpoint, then distribute new claims toward endpoints
     * with the lowest committed {@code CLAIMED} allocation before global due-time ties.
     *
     * <p>Package-visible so the PostgreSQL plan fixture executes the exact runtime query.
     */
    static String fairClaimCandidateSql(String endpointPlaceholders) {
        return "with current_endpoint_claims as ("
                + "select endpoint_id, count(*) as claim_count from public.deliveries "
                + "where state = 'CLAIMED' group by endpoint_id"
                + "), ranked_due as materialized ("
                + "select id, endpoint_id, due_at, row_number() over ("
                + "partition by endpoint_id order by due_at, id"
                + ") as endpoint_pending_ordinal from public.deliveries "
                + "where state = 'PENDING' and due_at <= CURRENT_TIMESTAMP and attempt_count < 5 "
                + "and endpoint_id in (" + endpointPlaceholders + ")"
                + ") select delivery.id, delivery.project_id, delivery.endpoint_id "
                + "from public.deliveries delivery "
                + "join ranked_due ranked on ranked.id = delivery.id "
                + "left join current_endpoint_claims current_claims "
                + "on current_claims.endpoint_id = ranked.endpoint_id "
                + "where delivery.state = 'PENDING' and delivery.due_at <= CURRENT_TIMESTAMP "
                + "and delivery.attempt_count < 5 "
                + "order by coalesce(current_claims.claim_count, 0) + ranked.endpoint_pending_ordinal, "
                + "ranked.due_at, ranked.endpoint_id, delivery.id "
                + "limit ? for update of delivery skip locked";
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
                        + "and not exists (select 1 from public.delivery_attempts attempt "
                        + "where attempt.delivery_id = deliveries.id and attempt.status = 'STARTED') "
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

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public DeliveryOperationalSnapshot currentOperationalSnapshot() {
        return jdbcTemplate.queryForObject(
                "select "
                        + "count(*) filter (where delivery.state = 'PENDING' and delivery.due_at <= CURRENT_TIMESTAMP "
                        + "and endpoint.enabled) as ready_due_count, "
                        + "count(*) filter (where delivery.state = 'PENDING' and delivery.due_at <= CURRENT_TIMESTAMP "
                        + "and not endpoint.enabled) as paused_due_count, "
                        + "count(*) filter (where delivery.state = 'CLAIMED') as claimed_count, "
                        + "min(delivery.due_at) filter (where delivery.state = 'PENDING' "
                        + "and delivery.due_at <= CURRENT_TIMESTAMP and endpoint.enabled) as oldest_ready_due_at "
                        + "from public.deliveries delivery "
                        + "join public.webhook_endpoints endpoint on endpoint.id = delivery.endpoint_id",
                (resultSet, rowNumber) -> new DeliveryOperationalSnapshot(
                        resultSet.getLong("ready_due_count"),
                        resultSet.getLong("paused_due_count"),
                        resultSet.getLong("claimed_count"),
                        instant(resultSet, "oldest_ready_due_at")
                )
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<UUID> findNextExpiredTerminalEvent(int retentionDays) {
        return jdbcTemplate.query(
                        "select event.id from public.events event "
                                + "where event.accepted_at < CURRENT_TIMESTAMP - make_interval(days => ?) "
                                + "and not exists (select 1 from public.deliveries delivery "
                                + "where delivery.event_id = event.id and (delivery.state not in "
                                + "('SUCCEEDED', 'FAILED_PERMANENT', 'EXHAUSTED') "
                                + "or delivery.terminal_at >= CURRENT_TIMESTAMP - make_interval(days => ?))) "
                                + "order by event.accepted_at, event.id limit 1",
                        (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                        retentionDays,
                        retentionDays
                )
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLockRetentionGraph(UUID eventId) {
        List<UUID> lockedDeliveryIds = jdbcTemplate.query(
                "select id from public.deliveries where event_id = ? order by id for update skip locked",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                eventId
        );
        Integer deliveryCount = jdbcTemplate.queryForObject(
                "select count(*) from public.deliveries where event_id = ?",
                Integer.class,
                eventId
        );
        return deliveryCount != null && lockedDeliveryIds.size() == deliveryCount;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockRetentionEvent(UUID eventId) {
        return !jdbcTemplate.query(
                        "select id from public.events where id = ? for update",
                        (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                        eventId
                )
                .isEmpty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isExpiredCompleteTerminalGraph(UUID eventId, int retentionDays) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from public.events event where event.id = ? "
                        + "and event.accepted_at < CURRENT_TIMESTAMP - make_interval(days => ?) "
                        + "and not exists (select 1 from public.deliveries delivery "
                        + "where delivery.event_id = event.id and (delivery.state not in "
                        + "('SUCCEEDED', 'FAILED_PERMANENT', 'EXHAUSTED') "
                        + "or delivery.terminal_at >= CURRENT_TIMESTAMP - make_interval(days => ?))))",
                Boolean.class,
                eventId,
                retentionDays,
                retentionDays
        ));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public RetentionCleanupResult deleteRetentionGraph(UUID eventId) {
        int diagnostics = jdbcTemplate.update(
                "delete from public.attempt_late_diagnostics diagnostic using public.delivery_attempts attempt "
                        + "join public.deliveries delivery on delivery.id = attempt.delivery_id "
                        + "where diagnostic.attempt_id = attempt.id and delivery.event_id = ?",
                eventId
        );
        int attempts = jdbcTemplate.update(
                "delete from public.delivery_attempts attempt using public.deliveries delivery "
                        + "where attempt.delivery_id = delivery.id and delivery.event_id = ?",
                eventId
        );
        int replayRequests = jdbcTemplate.update(
                "delete from public.replay_requests request using public.deliveries delivery "
                        + "where (request.source_delivery_id = delivery.id or request.replay_delivery_id = delivery.id) "
                        + "and delivery.event_id = ?",
                eventId
        );
        int deliveries = deleteRetentionLeaves(eventId);
        int events = jdbcTemplate.update("delete from public.events where id = ?", eventId);
        if (events != 1) {
            throw new IllegalStateException("retention event graph was not deleted atomically");
        }
        return new RetentionCleanupResult(events, deliveries, attempts, diagnostics, replayRequests);
    }

    private int deleteRetentionLeaves(UUID eventId) {
        int deleted = 0;
        while (true) {
            int leaves = jdbcTemplate.update(
                    "delete from public.deliveries delivery where delivery.event_id = ? "
                            + "and not exists (select 1 from public.deliveries child "
                            + "where child.replay_of_delivery_id = delivery.id)",
                    eventId
            );
            deleted += leaves;
            if (leaves == 0) {
                break;
            }
        }
        Integer remaining = jdbcTemplate.queryForObject(
                "select count(*) from public.deliveries where event_id = ?",
                Integer.class,
                eventId
        );
        if (remaining == null || remaining != 0) {
            throw new IllegalStateException("retention graph contains a replay lineage that cannot be removed safely");
        }
        return deleted;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AttemptStartCandidate> lockCurrentClaimForAttemptStart(ClaimedDelivery claim) {
        return jdbcTemplate.query(
                        "select delivery.id as delivery_id, delivery.project_id, delivery.endpoint_id, "
                                + "delivery.claim_token, delivery.attempt_count, event.id as event_id, "
                                + "event.event_type, event.accepted_at, event.payload::text as payload_json "
                                + "from public.deliveries delivery "
                                + "join public.events event on event.id = delivery.event_id "
                                + "and event.project_id = delivery.project_id "
                                + "where delivery.id = ? and delivery.project_id = ? and delivery.endpoint_id = ? "
                                + "and delivery.state = 'CLAIMED' and delivery.claim_token = ? "
                                + "and delivery.lease_expires_at > CURRENT_TIMESTAMP and delivery.attempt_count < 5 "
                                + "and not exists (select 1 from public.delivery_attempts attempt "
                                + "where attempt.delivery_id = delivery.id and attempt.status = 'STARTED') "
                                + "for update of delivery",
                        this::mapAttemptStartCandidate,
                        claim.deliveryId(),
                        claim.projectId(),
                        claim.endpointId(),
                        claim.claimToken()
                )
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean releaseClaimBeforeAttempt(AttemptStartCandidate candidate) {
        return jdbcTemplate.update(
                "update public.deliveries set state = 'PENDING', due_at = CURRENT_TIMESTAMP, claim_token = null, "
                        + "lease_expires_at = null, updated_at = CURRENT_TIMESTAMP "
                        + "where id = ? and project_id = ? and endpoint_id = ? and state = 'CLAIMED' "
                        + "and claim_token = ? and lease_expires_at > CURRENT_TIMESTAMP "
                        + "and attempt_count = ?",
                candidate.deliveryId(),
                candidate.projectId(),
                candidate.endpointId(),
                candidate.claimToken(),
                candidate.attemptCount()
        ) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<StartedAttempt> startAttempt(
            AttemptStartCandidate candidate,
            UUID attemptId,
            short destinationFingerprintVersion,
            byte[] destinationFingerprint,
            Duration attemptExecutionLease
    ) {
        List<StartedDelivery> startedDeliveries = jdbcTemplate.query(
                "update public.deliveries set attempt_count = attempt_count + 1, "
                        + "lease_expires_at = CURRENT_TIMESTAMP + (? * interval '1 millisecond'), "
                        + "updated_at = CURRENT_TIMESTAMP "
                        + "where id = ? and project_id = ? and endpoint_id = ? and state = 'CLAIMED' "
                        + "and claim_token = ? and lease_expires_at > CURRENT_TIMESTAMP and attempt_count = ? "
                        + "and not exists (select 1 from public.delivery_attempts attempt "
                        + "where attempt.delivery_id = deliveries.id and attempt.status = 'STARTED') "
                        + "returning attempt_count, lease_expires_at",
                this::mapStartedDelivery,
                attemptExecutionLease.toMillis(),
                candidate.deliveryId(),
                candidate.projectId(),
                candidate.endpointId(),
                candidate.claimToken(),
                candidate.attemptCount()
        );
        if (startedDeliveries.isEmpty()) {
            return Optional.empty();
        }

        StartedDelivery startedDelivery = startedDeliveries.getFirst();
        return jdbcTemplate.query(
                        "insert into public.delivery_attempts (id, delivery_id, attempt_number, claim_token, status, "
                                + "destination_fingerprint_version, destination_fingerprint, started_at, response_truncated) "
                                + "values (?, ?, ?, ?, 'STARTED', ?, ?, CURRENT_TIMESTAMP, false) "
                                + "returning started_at",
                        (resultSet, rowNumber) -> new StartedAttempt(
                                attemptId,
                                startedDelivery.attemptNumber(),
                                resultSet.getObject("started_at", OffsetDateTime.class).toInstant(),
                                startedDelivery.leaseExpiresAt()
                        ),
                        attemptId,
                        candidate.deliveryId(),
                        startedDelivery.attemptNumber(),
                        candidate.claimToken(),
                        destinationFingerprintVersion,
                        destinationFingerprint
                )
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean finalizeCurrentAttempt(
            DispatchInstruction instruction,
            AttemptCompletion completion,
            CompletionDecision decision
    ) {
        long retryDelayMilliseconds = decision.retryDelay() == null ? 0L : decision.retryDelay().toMillis();
        boolean pending = decision.deliveryState().name().equals("PENDING");
        boolean terminal = !pending;
        return !jdbcTemplate.query(
                "with locked as ("
                        + "select delivery.id as delivery_id, attempt.id as attempt_id "
                        + "from public.deliveries delivery "
                        + "join public.delivery_attempts attempt on attempt.delivery_id = delivery.id "
                        + "where delivery.id = ? and delivery.state = 'CLAIMED' "
                        + "and delivery.claim_token = ? and delivery.lease_expires_at > CURRENT_TIMESTAMP "
                        + "and delivery.attempt_count = ? and attempt.id = ? and attempt.claim_token = delivery.claim_token "
                        + "and attempt.attempt_number = ? and attempt.status = 'STARTED' "
                        + "for update of delivery, attempt"
                        + "), finalized_attempt as ("
                        + "update public.delivery_attempts attempt set status = ?, finished_at = CURRENT_TIMESTAMP, "
                        + "http_status = ?, failure_code = ?, latency_ms = ?, response_preview = ?, response_truncated = ? "
                        + "from locked where attempt.id = locked.attempt_id and attempt.status = 'STARTED' "
                        + "returning attempt.id"
                        + ") update public.deliveries delivery set state = ?, "
                        + "due_at = case when ? then CURRENT_TIMESTAMP + (? * interval '1 millisecond') else null end, "
                        + "claim_token = null, lease_expires_at = null, updated_at = CURRENT_TIMESTAMP, "
                        + "terminal_at = case when ? then CURRENT_TIMESTAMP else null end "
                        + "from locked, finalized_attempt where delivery.id = locked.delivery_id "
                        + "returning delivery.id",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                instruction.deliveryId(),
                instruction.claimToken(),
                instruction.attemptNumber(),
                instruction.attemptId(),
                instruction.attemptNumber(),
                completion.status().name(),
                completion.httpStatus(),
                completion.failureCode(),
                completion.latencyMilliseconds(),
                completion.responsePreview(),
                completion.responseTruncated(),
                decision.deliveryState().name(),
                pending,
                retryDelayMilliseconds,
                terminal
        ).isEmpty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public boolean hasCurrentLease(DispatchInstruction instruction, Duration minimumRemaining) {
        Boolean current = jdbcTemplate.queryForObject(
                "select exists("
                        + "select 1 from public.deliveries delivery "
                        + "join public.delivery_attempts attempt on attempt.delivery_id = delivery.id "
                        + "where delivery.id = ? and delivery.state = 'CLAIMED' and delivery.claim_token = ? "
                        + "and delivery.lease_expires_at >= CURRENT_TIMESTAMP + (? * interval '1 millisecond') "
                        + "and delivery.attempt_count = ? and attempt.id = ? and attempt.claim_token = delivery.claim_token "
                        + "and attempt.attempt_number = ? and attempt.status = 'STARTED'"
                        + ")",
                Boolean.class,
                instruction.deliveryId(),
                instruction.claimToken(),
                minimumRemaining.toMillis(),
                instruction.attemptNumber(),
                instruction.attemptId(),
                instruction.attemptNumber()
        );
        return Boolean.TRUE.equals(current);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordLateDiagnostic(
            DispatchInstruction instruction,
            AttemptCompletion completion,
            UUID diagnosticId
    ) {
        return !jdbcTemplate.query(
                "insert into public.attempt_late_diagnostics (id, attempt_id, claim_token, observed_status, "
                        + "http_status, failure_code, latency_ms, observed_at) "
                        + "select ?, attempt.id, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP "
                        + "from public.delivery_attempts attempt "
                        + "where attempt.id = ? and attempt.claim_token = ? and attempt.status = 'UNKNOWN' "
                        + "on conflict (attempt_id) do nothing returning id",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                diagnosticId,
                instruction.claimToken(),
                completion.status().name(),
                completion.httpStatus(),
                completion.failureCode(),
                completion.latencyMilliseconds(),
                instruction.attemptId(),
                instruction.claimToken()
        ).isEmpty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ExpiredStartedAttempt> lockExpiredStartedAttempts(int capacity) {
        return jdbcTemplate.query(
                "select delivery.id as delivery_id, attempt.id as attempt_id, delivery.claim_token, "
                        + "delivery.attempt_count from public.deliveries delivery "
                        + "join public.delivery_attempts attempt on attempt.delivery_id = delivery.id "
                        + "and attempt.claim_token = delivery.claim_token and attempt.status = 'STARTED' "
                        + "where delivery.state = 'CLAIMED' and delivery.lease_expires_at <= CURRENT_TIMESTAMP "
                        + "order by delivery.lease_expires_at, delivery.id limit ? "
                        + "for update of delivery, attempt skip locked",
                this::mapExpiredStartedAttempt,
                capacity
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recoverExpiredStartedAttempt(
            ExpiredStartedAttempt expiredAttempt,
            CompletionDecision decision
    ) {
        long retryDelayMilliseconds = decision.retryDelay() == null ? 0L : decision.retryDelay().toMillis();
        boolean pending = decision.deliveryState().name().equals("PENDING");
        boolean terminal = !pending;
        return !jdbcTemplate.query(
                "with locked as ("
                        + "select delivery.id as delivery_id, attempt.id as attempt_id "
                        + "from public.deliveries delivery "
                        + "join public.delivery_attempts attempt on attempt.delivery_id = delivery.id "
                        + "where delivery.id = ? and delivery.state = 'CLAIMED' and delivery.claim_token = ? "
                        + "and delivery.lease_expires_at <= CURRENT_TIMESTAMP and delivery.attempt_count = ? "
                        + "and attempt.id = ? and attempt.claim_token = delivery.claim_token "
                        + "and attempt.attempt_number = ? and attempt.status = 'STARTED' "
                        + "for update of delivery, attempt"
                        + "), finalized_attempt as ("
                        + "update public.delivery_attempts attempt set status = 'UNKNOWN', finished_at = CURRENT_TIMESTAMP, "
                        + "http_status = null, failure_code = null, latency_ms = null, response_preview = null, "
                        + "response_truncated = false from locked "
                        + "where attempt.id = locked.attempt_id and attempt.status = 'STARTED' returning attempt.id"
                        + ") update public.deliveries delivery set state = ?, "
                        + "due_at = case when ? then CURRENT_TIMESTAMP + (? * interval '1 millisecond') else null end, "
                        + "claim_token = null, lease_expires_at = null, updated_at = CURRENT_TIMESTAMP, "
                        + "terminal_at = case when ? then CURRENT_TIMESTAMP else null end "
                        + "from locked, finalized_attempt where delivery.id = locked.delivery_id returning delivery.id",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                expiredAttempt.deliveryId(),
                expiredAttempt.claimToken(),
                expiredAttempt.attemptNumber(),
                expiredAttempt.attemptId(),
                expiredAttempt.attemptNumber(),
                decision.deliveryState().name(),
                pending,
                retryDelayMilliseconds,
                terminal
        ).isEmpty();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<HistoryRecords.EventRecord> listHistoryEvents(
            UUID projectId,
            String eventType,
            EventHistoryCursor cursor,
            int fetchLimit
    ) {
        StringBuilder sql = new StringBuilder(
                "select event.id, event.event_type, event.accepted_at, null::text as payload_json, "
                        + "count(delivery.id) as delivery_count from public.events event "
                        + "left join public.deliveries delivery on delivery.event_id = event.id "
                        + "and delivery.project_id = event.project_id where event.project_id = ? "
        );
        List<Object> parameters = new ArrayList<>();
        parameters.add(projectId);
        if (eventType != null) {
            sql.append("and event.event_type = ? ");
            parameters.add(eventType);
        }
        if (cursor != null) {
            sql.append("and (event.accepted_at < ? or (event.accepted_at = ? and event.id < ?)) ");
            parameters.add(OffsetDateTime.ofInstant(cursor.acceptedAt(), java.time.ZoneOffset.UTC));
            parameters.add(OffsetDateTime.ofInstant(cursor.acceptedAt(), java.time.ZoneOffset.UTC));
            parameters.add(cursor.eventId());
        }
        sql.append("group by event.id, event.event_type, event.accepted_at "
                + "order by event.accepted_at desc, event.id desc limit ?");
        parameters.add(fetchLimit);
        return jdbcTemplate.query(sql.toString(), this::mapHistoryEvent, parameters.toArray());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<HistoryRecords.EventRecord> findHistoryEvent(UUID projectId, UUID eventId) {
        return jdbcTemplate.query(
                        "select event.id, event.event_type, event.accepted_at, event.payload::text as payload_json, "
                                + "count(delivery.id) as delivery_count from public.events event "
                                + "left join public.deliveries delivery on delivery.event_id = event.id "
                                + "and delivery.project_id = event.project_id "
                                + "where event.project_id = ? and event.id = ? "
                                + "group by event.id, event.event_type, event.accepted_at, event.payload",
                        this::mapHistoryEvent,
                        projectId,
                        eventId
                )
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public EventDeliverySummary summarizeEventDeliveries(UUID projectId, UUID eventId) {
        return jdbcTemplate.queryForObject(
                "select count(*) as total_count, "
                        + "count(*) filter (where state in ('PENDING', 'CLAIMED')) as active_count, "
                        + "count(*) filter (where state = 'SUCCEEDED') as succeeded_count, "
                        + "count(*) filter (where state = 'FAILED_PERMANENT') as failed_permanent_count, "
                        + "count(*) filter (where state = 'EXHAUSTED') as exhausted_count "
                        + "from public.deliveries where project_id = ? and event_id = ?",
                (resultSet, rowNumber) -> new EventDeliverySummary(
                        resultSet.getInt("total_count"),
                        resultSet.getInt("active_count"),
                        resultSet.getInt("succeeded_count"),
                        resultSet.getInt("failed_permanent_count"),
                        resultSet.getInt("exhausted_count")
                ),
                projectId,
                eventId
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<HistoryRecords.DeliveryRecord> listEventHistoryDeliveries(
            UUID projectId,
            UUID eventId,
            DeliveryHistoryCursor cursor,
            int fetchLimit
    ) {
        StringBuilder sql = new StringBuilder(
                "select " + HISTORY_DELIVERY_COLUMNS + " from public.deliveries delivery "
                        + "where delivery.project_id = ? and delivery.event_id = ? "
        );
        List<Object> parameters = new ArrayList<>(List.of(projectId, eventId));
        if (cursor != null) {
            sql.append("and (delivery.created_at > ? or (delivery.created_at = ? and delivery.id > ?)) ");
            parameters.add(OffsetDateTime.ofInstant(cursor.createdAt(), java.time.ZoneOffset.UTC));
            parameters.add(OffsetDateTime.ofInstant(cursor.createdAt(), java.time.ZoneOffset.UTC));
            parameters.add(cursor.deliveryId());
        }
        sql.append("order by delivery.created_at asc, delivery.id asc limit ?");
        parameters.add(fetchLimit);
        return jdbcTemplate.query(sql.toString(), this::mapHistoryDelivery, parameters.toArray());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<HistoryRecords.DeliveryRecord> listProjectHistoryDeliveries(
            UUID projectId,
            UUID eventId,
            UUID endpointId,
            DeliveryDisplayStatus displayStatus,
            Collection<UUID> enabledEndpointIds,
            DeliveryHistoryCursor cursor,
            int fetchLimit
    ) {
        StringBuilder sql = new StringBuilder(
                "select " + HISTORY_DELIVERY_COLUMNS + " from public.deliveries delivery where delivery.project_id = ? "
        );
        List<Object> parameters = new ArrayList<>();
        parameters.add(projectId);
        if (eventId != null) {
            sql.append("and delivery.event_id = ? ");
            parameters.add(eventId);
        }
        if (endpointId != null) {
            sql.append("and delivery.endpoint_id = ? ");
            parameters.add(endpointId);
        }
        appendDisplayStatusPredicate(sql, parameters, displayStatus, List.copyOf(enabledEndpointIds));
        if (cursor != null) {
            sql.append("and (delivery.created_at < ? or (delivery.created_at = ? and delivery.id < ?)) ");
            parameters.add(OffsetDateTime.ofInstant(cursor.createdAt(), java.time.ZoneOffset.UTC));
            parameters.add(OffsetDateTime.ofInstant(cursor.createdAt(), java.time.ZoneOffset.UTC));
            parameters.add(cursor.deliveryId());
        }
        sql.append("order by delivery.created_at desc, delivery.id desc limit ?");
        parameters.add(fetchLimit);
        return jdbcTemplate.query(sql.toString(), this::mapHistoryDelivery, parameters.toArray());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<HistoryRecords.DeliveryDetailRecord> findHistoryDelivery(UUID projectId, UUID deliveryId) {
        return jdbcTemplate.query(
                        "select " + HISTORY_DELIVERY_COLUMNS + ", event.event_type, "
                                + "attempt.id as latest_attempt_id, attempt.attempt_number as latest_attempt_number, "
                                + "attempt.status as latest_attempt_status, attempt.started_at as latest_attempt_started_at, "
                                + "attempt.finished_at as latest_attempt_finished_at, attempt.http_status as latest_attempt_http_status, "
                                + "attempt.failure_code as latest_attempt_failure_code, "
                                + "attempt.latency_ms as latest_attempt_latency_ms "
                                + "from public.deliveries delivery join public.events event "
                                + "on event.id = delivery.event_id and event.project_id = delivery.project_id "
                                + "left join lateral (select * from public.delivery_attempts candidate "
                                + "where candidate.delivery_id = delivery.id order by candidate.attempt_number desc limit 1) attempt "
                                + "on true where delivery.project_id = ? and delivery.id = ?",
                        this::mapHistoryDeliveryDetail,
                        projectId,
                        deliveryId
                )
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<UUID> findReplayDeliveryIds(UUID projectId, UUID deliveryId) {
        return jdbcTemplate.queryForList(
                "select id from public.deliveries where project_id = ? and replay_of_delivery_id = ? order by created_at asc, id asc",
                UUID.class,
                projectId,
                deliveryId
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<AttemptHistorySummary> listHistoryAttempts(UUID projectId, UUID deliveryId) {
        return jdbcTemplate.query(
                "select attempt.id as attempt_id, attempt.attempt_number, attempt.status, attempt.started_at, "
                        + "attempt.finished_at, attempt.http_status, attempt.failure_code, attempt.latency_ms "
                        + "from public.delivery_attempts attempt join public.deliveries delivery "
                        + "on delivery.id = attempt.delivery_id where delivery.project_id = ? and delivery.id = ? "
                        + "order by attempt.attempt_number asc",
                this::mapAttemptSummary,
                projectId,
                deliveryId
        );
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<HistoryRecords.AttemptDetailRecord> findHistoryAttempt(UUID projectId, UUID deliveryId, UUID attemptId) {
        return jdbcTemplate.query(
                        "select attempt.id as attempt_id, attempt.attempt_number, attempt.status, attempt.started_at, "
                                + "attempt.finished_at, attempt.http_status, attempt.failure_code, attempt.latency_ms, "
                                + "attempt.destination_fingerprint_version, attempt.destination_fingerprint, "
                                + "attempt.response_preview, attempt.response_truncated, diagnostic.observed_status, "
                                + "diagnostic.http_status as diagnostic_http_status, "
                                + "diagnostic.failure_code as diagnostic_failure_code, "
                                + "diagnostic.latency_ms as diagnostic_latency_ms, diagnostic.observed_at "
                                + "from public.delivery_attempts attempt join public.deliveries delivery "
                                + "on delivery.id = attempt.delivery_id left join public.attempt_late_diagnostics diagnostic "
                                + "on diagnostic.attempt_id = attempt.id where delivery.project_id = ? "
                                + "and delivery.id = ? and attempt.id = ?",
                        this::mapHistoryAttemptDetail,
                        projectId,
                        deliveryId,
                        attemptId
                )
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public HistoryRecords.ReplayResult replay(
            UUID projectId,
            UUID sourceDeliveryId,
            String idempotencyKey,
            UUID replayRequestId,
            UUID replayDeliveryId
    ) {
        Optional<ReplayDeliveryResult> existing = findReplayByKey(projectId, idempotencyKey);
        if (existing.isPresent()) {
            return existing.orElseThrow().sourceDeliveryId().equals(sourceDeliveryId)
                    ? new HistoryRecords.ReplayResult(HistoryRecords.ReplayOutcome.EXISTING, existing.orElseThrow())
                    : new HistoryRecords.ReplayResult(HistoryRecords.ReplayOutcome.CONFLICT, null);
        }

        Optional<ReplaySource> source = jdbcTemplate.query(
                        "select event_id, endpoint_id, state from public.deliveries "
                                + "where project_id = ? and id = ? for update",
                        (resultSet, rowNumber) -> new ReplaySource(
                                resultSet.getObject("event_id", UUID.class),
                                resultSet.getObject("endpoint_id", UUID.class),
                                resultSet.getString("state")
                        ),
                        projectId,
                        sourceDeliveryId
                )
                .stream()
                .findFirst();
        if (source.isEmpty()) {
            return new HistoryRecords.ReplayResult(HistoryRecords.ReplayOutcome.SOURCE_NOT_FOUND, null);
        }
        ReplaySource lockedSource = source.orElseThrow();
        if (!"EXHAUSTED".equals(lockedSource.state())) {
            return new HistoryRecords.ReplayResult(HistoryRecords.ReplayOutcome.SOURCE_NOT_EXHAUSTED, null);
        }

        boolean insertedRequest = !jdbcTemplate.query(
                "insert into public.replay_requests "
                        + "(id, project_id, idempotency_key, source_delivery_id, replay_delivery_id) "
                        + "values (?, ?, ?, ?, ?) on conflict (project_id, idempotency_key) do nothing returning id",
                (resultSet, rowNumber) -> resultSet.getObject("id", UUID.class),
                replayRequestId,
                projectId,
                idempotencyKey,
                sourceDeliveryId,
                replayDeliveryId
        ).isEmpty();
        if (!insertedRequest) {
            ReplayDeliveryResult replay = findReplayByKey(projectId, idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("replay idempotency row was not visible after conflict"));
            return replay.sourceDeliveryId().equals(sourceDeliveryId)
                    ? new HistoryRecords.ReplayResult(HistoryRecords.ReplayOutcome.EXISTING, replay)
                    : new HistoryRecords.ReplayResult(HistoryRecords.ReplayOutcome.CONFLICT, null);
        }

        java.time.Instant createdAt = jdbcTemplate.query(
                        "insert into public.deliveries "
                                + "(id, project_id, event_id, endpoint_id, replay_of_delivery_id, state, due_at, attempt_count) "
                                + "values (?, ?, ?, ?, ?, 'PENDING', CURRENT_TIMESTAMP, 0) returning created_at",
                        (resultSet, rowNumber) -> resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                        replayDeliveryId,
                        projectId,
                        lockedSource.eventId(),
                        lockedSource.endpointId(),
                        sourceDeliveryId
                )
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("inserted replay delivery was not returned"));
        return new HistoryRecords.ReplayResult(
                HistoryRecords.ReplayOutcome.CREATED,
                new ReplayDeliveryResult(
                        sourceDeliveryId,
                        replayDeliveryId,
                        lockedSource.eventId(),
                        lockedSource.endpointId(),
                        createdAt,
                        false
                )
        );
    }

    private Optional<ReplayDeliveryResult> findReplayByKey(UUID projectId, String idempotencyKey) {
        return jdbcTemplate.query(
                        "select request.source_delivery_id, request.replay_delivery_id, delivery.event_id, "
                                + "delivery.endpoint_id, delivery.created_at from public.replay_requests request "
                                + "join public.deliveries delivery on delivery.id = request.replay_delivery_id "
                                + "and delivery.project_id = request.project_id where request.project_id = ? "
                                + "and request.idempotency_key = ?",
                        (resultSet, rowNumber) -> new ReplayDeliveryResult(
                                resultSet.getObject("source_delivery_id", UUID.class),
                                resultSet.getObject("replay_delivery_id", UUID.class),
                                resultSet.getObject("event_id", UUID.class),
                                resultSet.getObject("endpoint_id", UUID.class),
                                resultSet.getObject("created_at", OffsetDateTime.class).toInstant(),
                                true
                        ),
                        projectId,
                        idempotencyKey
                )
                .stream()
                .findFirst();
    }

    private static void appendDisplayStatusPredicate(
            StringBuilder sql,
            List<Object> parameters,
            DeliveryDisplayStatus displayStatus,
            List<UUID> enabledEndpointIds
    ) {
        if (displayStatus == null) {
            return;
        }
        switch (displayStatus) {
            case SUCCEEDED, FAILED_PERMANENT, EXHAUSTED -> sql.append("and delivery.state = '")
                    .append(displayStatus.name())
                    .append("' ");
            case PAUSED -> {
                sql.append("and delivery.state in ('PENDING', 'CLAIMED') ");
                if (!enabledEndpointIds.isEmpty()) {
                    appendEndpointMembership(sql, parameters, enabledEndpointIds, false);
                }
            }
            case CLAIMED -> {
                sql.append("and delivery.state = 'CLAIMED' ");
                appendEndpointMembership(sql, parameters, enabledEndpointIds, true);
            }
            case RETRY_SCHEDULED -> {
                sql.append("and delivery.state = 'PENDING' and delivery.attempt_count > 0 "
                        + "and delivery.due_at > CURRENT_TIMESTAMP ");
                appendEndpointMembership(sql, parameters, enabledEndpointIds, true);
            }
            case PENDING -> {
                sql.append("and delivery.state = 'PENDING' and (delivery.attempt_count = 0 "
                        + "or delivery.due_at <= CURRENT_TIMESTAMP) ");
                appendEndpointMembership(sql, parameters, enabledEndpointIds, true);
            }
        }
    }

    private static void appendEndpointMembership(
            StringBuilder sql,
            List<Object> parameters,
            List<UUID> endpointIds,
            boolean expectedMembership
    ) {
        if (endpointIds.isEmpty()) {
            sql.append("and false ");
            return;
        }
        sql.append("and delivery.endpoint_id ");
        if (!expectedMembership) {
            sql.append("not ");
        }
        sql.append("in (")
                .append(String.join(", ", java.util.Collections.nCopies(endpointIds.size(), "?")))
                .append(") ");
        parameters.addAll(endpointIds);
    }

    private HistoryRecords.EventRecord mapHistoryEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new HistoryRecords.EventRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getString("event_type"),
                instant(resultSet, "accepted_at"),
                resultSet.getString("payload_json"),
                resultSet.getInt("delivery_count")
        );
    }

    private HistoryRecords.DeliveryRecord mapHistoryDelivery(ResultSet resultSet, int rowNumber) throws SQLException {
        return new HistoryRecords.DeliveryRecord(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("endpoint_id", UUID.class),
                resultSet.getObject("replay_of_delivery_id", UUID.class),
                DeliveryStoredState.valueOf(resultSet.getString("state")),
                resultSet.getInt("attempt_count"),
                instant(resultSet, "due_at"),
                resultSet.getBoolean("retry_scheduled"),
                instant(resultSet, "created_at"),
                instant(resultSet, "terminal_at")
        );
    }

    private HistoryRecords.DeliveryDetailRecord mapHistoryDeliveryDetail(ResultSet resultSet, int rowNumber)
            throws SQLException {
        HistoryRecords.DeliveryRecord delivery = mapHistoryDelivery(resultSet, rowNumber);
        UUID latestAttemptId = resultSet.getObject("latest_attempt_id", UUID.class);
        AttemptHistorySummary latestAttempt = latestAttemptId == null ? null : new AttemptHistorySummary(
                latestAttemptId,
                resultSet.getShort("latest_attempt_number"),
                AttemptHistoryStatus.valueOf(resultSet.getString("latest_attempt_status")),
                instant(resultSet, "latest_attempt_started_at"),
                instant(resultSet, "latest_attempt_finished_at"),
                resultSet.getObject("latest_attempt_http_status", Integer.class),
                resultSet.getString("latest_attempt_failure_code"),
                resultSet.getObject("latest_attempt_latency_ms", Integer.class)
        );
        return new HistoryRecords.DeliveryDetailRecord(delivery, resultSet.getString("event_type"), latestAttempt);
    }

    private AttemptHistorySummary mapAttemptSummary(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AttemptHistorySummary(
                resultSet.getObject("attempt_id", UUID.class),
                resultSet.getShort("attempt_number"),
                AttemptHistoryStatus.valueOf(resultSet.getString("status")),
                instant(resultSet, "started_at"),
                instant(resultSet, "finished_at"),
                resultSet.getObject("http_status", Integer.class),
                resultSet.getString("failure_code"),
                resultSet.getObject("latency_ms", Integer.class)
        );
    }

    private HistoryRecords.AttemptDetailRecord mapHistoryAttemptDetail(ResultSet resultSet, int rowNumber)
            throws SQLException {
        AttemptHistorySummary summary = mapAttemptSummary(resultSet, rowNumber);
        String observedStatus = resultSet.getString("observed_status");
        HistoryRecords.LateDiagnosticRecord diagnostic = observedStatus == null ? null : new HistoryRecords.LateDiagnosticRecord(
                AttemptHistoryStatus.valueOf(observedStatus),
                resultSet.getObject("diagnostic_http_status", Integer.class),
                resultSet.getString("diagnostic_failure_code"),
                resultSet.getObject("diagnostic_latency_ms", Integer.class),
                instant(resultSet, "observed_at")
        );
        return new HistoryRecords.AttemptDetailRecord(
                summary,
                resultSet.getShort("destination_fingerprint_version"),
                resultSet.getBytes("destination_fingerprint"),
                resultSet.getBytes("response_preview"),
                resultSet.getBoolean("response_truncated"),
                diagnostic
        );
    }

    private static java.time.Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
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

    private AttemptStartCandidate mapAttemptStartCandidate(ResultSet resultSet, int rowNumber) throws SQLException {
        return new AttemptStartCandidate(
                resultSet.getObject("delivery_id", UUID.class),
                resultSet.getObject("project_id", UUID.class),
                resultSet.getObject("endpoint_id", UUID.class),
                resultSet.getObject("event_id", UUID.class),
                resultSet.getObject("claim_token", UUID.class),
                resultSet.getInt("attempt_count"),
                resultSet.getString("event_type"),
                resultSet.getObject("accepted_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("payload_json").getBytes(StandardCharsets.UTF_8)
        );
    }

    private StartedDelivery mapStartedDelivery(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StartedDelivery(
                resultSet.getInt("attempt_count"),
                resultSet.getObject("lease_expires_at", OffsetDateTime.class).toInstant()
        );
    }

    private ExpiredStartedAttempt mapExpiredStartedAttempt(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ExpiredStartedAttempt(
                resultSet.getObject("delivery_id", UUID.class),
                resultSet.getObject("attempt_id", UUID.class),
                resultSet.getObject("claim_token", UUID.class),
                resultSet.getInt("attempt_count")
        );
    }

    private record StartedDelivery(int attemptNumber, java.time.Instant leaseExpiresAt) {
    }

    private record ReplaySource(UUID eventId, UUID endpointId, String state) {
    }
}
