package com.gialong.relayforge.runtime.publisher;

import com.gialong.relayforge.delivery.api.publish.EventPublisher;
import com.gialong.relayforge.project.api.VerifiedPublisherProject;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class PublisherEventRateLimitControllerTests {

    @Test
    void rejectsBeforeReadingBodyOrInvokingPublishAndReturnsRetryAfter() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            PublisherEventRateLimiter limiter = new PublisherEventRateLimiter(() -> 0, meters);
            UUID projectId = UUID.randomUUID();
            for (int request = 0; request < PublisherEventRateLimiter.CAPACITY; request++) {
                assertThat(limiter.admit(projectId).admitted()).isTrue();
            }
            EventPublisher publisher = (acceptedProjectId, idempotencyKey, eventType, payloadJson) -> {
                throw new AssertionError("rate-limited request must not publish");
            };
            PublisherEventController controller = new PublisherEventController(publisher, new ObjectMapper(), limiter);
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects/" + projectId + "/events");
            request.setContent("not read".getBytes());

            Throwable thrown = catchThrowable(() ->
                    controller.publish(
                            new UsernamePasswordAuthenticationToken(
                                    new VerifiedPublisherProject(projectId, UUID.randomUUID()),
                                    "ignored",
                                    List.of()
                            ),
                            projectId,
                            "same-idempotency-key",
                            request
                    )
            );
            assertThat(thrown).isInstanceOf(PublisherRateLimitExceededException.class);
            PublisherRateLimitExceededException exception = (PublisherRateLimitExceededException) thrown;

            ResponseEntity<ProblemDetail> response = new PublisherEventExceptionHandler().publisherRateLimited(exception);
            assertThat(response.getStatusCode().value()).isEqualTo(429);
            assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
            assertThat(response.getBody().getProperties()).containsEntry("code", "PUBLISH_RATE_LIMITED");
        } finally {
            meters.close();
        }
    }
}
