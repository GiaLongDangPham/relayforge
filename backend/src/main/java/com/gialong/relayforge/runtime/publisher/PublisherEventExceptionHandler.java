package com.gialong.relayforge.runtime.publisher;

import com.gialong.relayforge.delivery.api.PublishIdempotencyConflictException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice(basePackageClasses = PublisherEventController.class)
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class PublisherEventExceptionHandler {

    @ExceptionHandler(PublisherProjectForbiddenException.class)
    ProblemDetail publisherProjectForbidden() {
        return problem(HttpStatus.FORBIDDEN, "PROJECT_KEY_MISMATCH", "Project key mismatch", "The API key cannot publish to this project.");
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail missingIdempotencyKey(MissingRequestHeaderException exception) {
        if ("Idempotency-Key".equals(exception.getHeaderName())) {
            return problem(
                    HttpStatus.BAD_REQUEST,
                    "MISSING_IDEMPOTENCY_KEY",
                    "Missing idempotency key",
                    "The Idempotency-Key header is required."
            );
        }
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation failed", "The request is invalid.");
    }

    @ExceptionHandler(PublisherMalformedJsonException.class)
    ProblemDetail malformedJson() {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Malformed JSON", "The request body is not valid JSON.");
    }

    @ExceptionHandler(PublishIdempotencyConflictException.class)
    ProblemDetail idempotencyConflict() {
        return problem(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "Idempotency key conflict",
                "The key is already associated with a different command."
        );
    }

    @ExceptionHandler(PublisherRequestTooLargeException.class)
    ProblemDetail requestTooLarge() {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "Payload too large", "The request body exceeds 64 KiB.");
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
