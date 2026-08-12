package com.gialong.relayforge.runtime.endpoints;

import com.gialong.relayforge.endpoint.api.WebhookEndpointVersionConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class WebhookEndpointExceptionHandler {

    @ExceptionHandler(EndpointNotFoundException.class)
    ProblemDetail notFound() {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found", "The resource was not found.");
    }

    @ExceptionHandler(WebhookEndpointVersionConflictException.class)
    ProblemDetail versionConflict() {
        return problem(
                HttpStatus.CONFLICT,
                "OPTIMISTIC_LOCK_CONFLICT",
                "Optimistic lock conflict",
                "The endpoint has changed. Reload it before trying again."
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail invalidRequest() {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", "The request is invalid.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create("urn:relayforge:problem:" + code.toLowerCase().replace('_', '-')));
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
