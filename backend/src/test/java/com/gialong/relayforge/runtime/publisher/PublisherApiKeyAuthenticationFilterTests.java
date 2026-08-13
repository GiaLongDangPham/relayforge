package com.gialong.relayforge.runtime.publisher;

import com.gialong.relayforge.project.api.PublisherApiKeyVerifier;
import com.gialong.relayforge.project.api.VerifiedPublisherProject;
import com.gialong.relayforge.runtime.security.SecurityProblemWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PublisherApiKeyAuthenticationFilterTests {

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void replacesAnyDashboardSessionWithVerifiedPublisherAuthenticationForPublishRequest() throws Exception {
        UUID projectId = UUID.randomUUID();
        VerifiedPublisherProject verified = new VerifiedPublisherProject(projectId, UUID.randomUUID());
        PublisherApiKeyVerifier verifier = rawKey -> "publisher-key".equals(rawKey)
                ? Optional.of(verified)
                : Optional.empty();
        PublisherApiKeyAuthenticationFilter filter = filter(verifier);
        MockHttpServletRequest request = publishRequest();
        request.addHeader("Authorization", "Bearer publisher-key");
        MockHttpServletResponse response = new MockHttpServletResponse();
        Authentication dashboardAuthentication = UsernamePasswordAuthenticationToken.authenticated("owner", null, java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(dashboardAuthentication);
        AtomicReference<Authentication> authenticatedDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) ->
                authenticatedDuringChain.set(SecurityContextHolder.getContext().getAuthentication())
        );

        assertThat(authenticatedDuringChain.get().getPrincipal()).isSameAs(verified);
        assertThat(authenticatedDuringChain.get().getCredentials()).isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isSameAs(dashboardAuthentication);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void rejectsMissingOrInvalidBearerCredentialsBeforeControllerExecution() throws Exception {
        PublisherApiKeyAuthenticationFilter filter = filter(rawKey -> Optional.empty());
        MockHttpServletRequest request = publishRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> chainCalled = new AtomicReference<>(false);

        filter.doFilter(request, response, (ignoredRequest, ignoredResponse) -> chainCalled.set(true));

        assertThat(chainCalled.get()).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("INVALID_API_KEY");
    }

    private static PublisherApiKeyAuthenticationFilter filter(PublisherApiKeyVerifier verifier) {
        return new PublisherApiKeyAuthenticationFilter(verifier, new SecurityProblemWriter(new ObjectMapper()));
    }

    private static MockHttpServletRequest publishRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects/" + UUID.randomUUID() + "/events");
        request.setServletPath(request.getRequestURI());
        return request;
    }
}
