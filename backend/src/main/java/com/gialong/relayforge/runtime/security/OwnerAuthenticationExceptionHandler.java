package com.gialong.relayforge.runtime.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class OwnerAuthenticationExceptionHandler {

    @ExceptionHandler(BadCredentialsException.class)
    ProblemDetail invalidCredentials() {
        return problem(
                HttpStatus.UNAUTHORIZED,
                "INVALID_OWNER_CREDENTIALS",
                "Invalid owner credentials",
                "The supplied credentials are invalid."
        );
    }

    @ExceptionHandler(OwnerLoginRateLimitExceededException.class)
    ProblemDetail rateLimited() {
        return problem(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                "Too many login attempts",
                "Try again later."
        );
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:relayforge:problem:" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
