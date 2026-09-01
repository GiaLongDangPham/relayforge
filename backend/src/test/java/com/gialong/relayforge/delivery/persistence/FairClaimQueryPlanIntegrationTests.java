package com.gialong.relayforge.delivery.persistence;
import com.gialong.relayforge.delivery.api.publish.EventPublisher;

import com.gialong.relayforge.RelayForgeApplication;
import com.gialong.relayforge.delivery.api.publish.EventPublisher;
import com.gialong.relayforge.endpoint.api.WebhookEndpointCatalog;
import com.gialong.relayforge.endpoint.api.WebhookEndpointDetails;
import com.gialong.relayforge.identity.api.OwnerBootstrap;
import com.gialong.relayforge.identity.api.OwnerBootstrapResult;
import com.gialong.relayforge.project.api.ProjectCatalog;
import com.gialong.relayforge.project.api.ProjectDetails;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(
        classes = RelayForgeApplication.class,
        properties = {"relayforge.runtime=worker", "relayforge.worker.lifecycle-enabled=false"},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class FairClaimQueryPlanIntegrationTests {

    private static final Logger LOGGER = LoggerFactory.getLogger(FairClaimQueryPlanIntegrationTests.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine")
            .withDatabaseName("relayforge_fair_claim_plan_test")
            .withUsername("relayforge_fair_claim_plan_test")
            .withPassword("relayforge_fair_claim_plan_test");

    @Autowired
    private OwnerBootstrap ownerBootstrap;

    @Autowired
    private ProjectCatalog projectCatalog;

    @Autowired
    private WebhookEndpointCatalog endpointCatalog;

    @Autowired
    private EventPublisher eventPublisher;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void clearFixture() {
        jdbcTemplate.update("delete from endpoint_circuit_breakers");
        jdbcTemplate.update("delete from attempt_late_diagnostics");
        jdbcTemplate.update("delete from delivery_attempts");
        jdbcTemplate.update("delete from replay_requests");
        jdbcTemplate.update("delete from deliveries");
        jdbcTemplate.update("delete from endpoint_subscriptions");
        jdbcTemplate.update("delete from webhook_endpoints");
        jdbcTemplate.update("delete from events");
        jdbcTemplate.update("delete from project_api_keys");
        jdbcTemplate.update("delete from project_publish_quota_usage");
        jdbcTemplate.update("delete from projects");
        jdbcTemplate.update("delete from owner_accounts");
    }

    @Test
    void capturesTheLiveFairClaimPlanForTwoDeepBacklogs() throws Exception {
        OwnerBootstrapResult owner = bootstrap("claim.plan.owner");
        ProjectDetails project = projectCatalog.create(owner.ownerId(), "Fair claim plan fixture");
        WebhookEndpointDetails firstEndpoint = endpoint(owner.ownerId(), project.id(), "Plan first receiver");
        WebhookEndpointDetails secondEndpoint = endpoint(owner.ownerId(), project.id(), "Plan second receiver");

        for (int index = 0; index < 64; index++) {
            eventPublisher.publish(project.id(), "plan-event-" + index, "fair.plan", "{\"sequence\":" + index + "}");
        }
        jdbcTemplate.update(
                "update deliveries set due_at = CURRENT_TIMESTAMP - interval '60 seconds' where endpoint_id = ?",
                firstEndpoint.id()
        );
        jdbcTemplate.update(
                "update deliveries set due_at = CURRENT_TIMESTAMP - interval '30 seconds' where endpoint_id = ?",
                secondEndpoint.id()
        );

        String planJson = jdbcTemplate.queryForObject(
                "explain (analyze, buffers, format json) " + JdbcDeliveryStore.fairClaimCandidateSql("?, ?"),
                String.class,
                firstEndpoint.id(),
                secondEndpoint.id(),
                8
        );
        JsonNode explain = JSON.readTree(planJson).get(0);
        JsonNode plan = explain.path("Plan");
        List<String> nodes = new ArrayList<>();
        collectPlanNodes(plan, nodes);

        assertThat(plan.path("Actual Rows").asLong()).isEqualTo(8);
        assertThat(nodes).isNotEmpty();
        LOGGER.info(
                "Phase 2A fair-claim plan: actualRows={}, executionMs={}, planningMs={}, rootSharedHitBlocks={}, nodes={}",
                plan.path("Actual Rows").asLong(),
                explain.path("Execution Time").asDouble(),
                explain.path("Planning Time").asDouble(),
                plan.path("Shared Hit Blocks").asLong(),
                nodes
        );
    }

    private WebhookEndpointDetails endpoint(UUID ownerId, UUID projectId, String name) {
        return endpointCatalog.create(
                ownerId,
                projectId,
                name,
                "https://" + name.toLowerCase().replace(' ', '-') + ".example/webhooks",
                List.of("fair.plan"),
                true
        ).orElseThrow().endpoint();
    }

    private OwnerBootstrapResult bootstrap(String loginName) {
        char[] password = "fair-claim-plan-test-password".toCharArray();
        try {
            return ownerBootstrap.bootstrap(loginName, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private static void collectPlanNodes(JsonNode node, List<String> nodes) {
        String nodeType = textOrEmpty(node, "Node Type");
        String relation = textOrEmpty(node, "Relation Name");
        String index = textOrEmpty(node, "Index Name");
        nodes.add(nodeType + (relation.isBlank() ? "" : "(" + relation + ")")
                + (index.isBlank() ? "" : " via " + index));
        for (JsonNode child : node.path("Plans")) {
            collectPlanNodes(child, nodes);
        }
    }

    private static String textOrEmpty(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : "";
    }
}
