package com.gialong.relayforge.runtime.publisher;

import com.gialong.relayforge.project.api.PublisherApiKeyVerifier;
import com.gialong.relayforge.project.api.VerifiedPublisherProject;
import com.gialong.relayforge.runtime.security.SecurityProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.util.matcher.RegexRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Publisher authentication is request-only: dashboard sessions never authorize a publish command.
 */
public final class PublisherApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final RequestMatcher PUBLISH_REQUEST = RegexRequestMatcher.regexMatcher(
            HttpMethod.POST,
            "/api/v1/projects/[^/]+/events"
    );

    private final PublisherApiKeyVerifier publisherApiKeyVerifier;
    private final SecurityProblemWriter securityProblemWriter;

    public PublisherApiKeyAuthenticationFilter(
            PublisherApiKeyVerifier publisherApiKeyVerifier,
            SecurityProblemWriter securityProblemWriter
    ) {
        this.publisherApiKeyVerifier = Objects.requireNonNull(
                publisherApiKeyVerifier,
                "publisherApiKeyVerifier must not be null"
        );
        this.securityProblemWriter = Objects.requireNonNull(securityProblemWriter, "securityProblemWriter must not be null");
    }

    public static RequestMatcher publishRequestMatcher() {
        return PUBLISH_REQUEST;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PUBLISH_REQUEST.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Optional<VerifiedPublisherProject> verified = bearerToken(request)
                .flatMap(publisherApiKeyVerifier::verify);
        if (verified.isEmpty()) {
            securityProblemWriter.write(
                    response,
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_API_KEY",
                    "Invalid API key",
                    "A valid publisher API key is required."
            );
            return;
        }

        SecurityContext originalContext = SecurityContextHolder.getContext();
        SecurityContext publisherContext = SecurityContextHolder.createEmptyContext();
        publisherContext.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                verified.orElseThrow(),
                null,
                List.of()
        ));
        SecurityContextHolder.setContext(publisherContext);
        try {
            filterChain.doFilter(request, response);
        } finally {
            SecurityContextHolder.setContext(originalContext);
        }
    }

    private static Optional<String> bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return Optional.empty();
        }
        String token = authorization.substring("Bearer ".length());
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }
}
