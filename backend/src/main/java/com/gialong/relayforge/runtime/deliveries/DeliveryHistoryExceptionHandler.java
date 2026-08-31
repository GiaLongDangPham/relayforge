package com.gialong.relayforge.runtime.deliveries;
import com.gialong.relayforge.delivery.api.replay.ReplayIdempotencyConflictException;
import com.gialong.relayforge.delivery.api.replay.ReplayInvalidStateException;

import com.gialong.relayforge.delivery.api.replay.ReplayIdempotencyConflictException;
import com.gialong.relayforge.delivery.api.replay.ReplayInvalidStateException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;

@RestControllerAdvice(basePackageClasses = DeliveryHistoryController.class)
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class DeliveryHistoryExceptionHandler {

    @ExceptionHandler(DeliveryHistoryNotFoundException.class)
    ProblemDetail notFound() {
        return problem(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource not found", "The resource was not found.");
    }

    @ExceptionHandler(ReplayIdempotencyConflictException.class)
    ProblemDetail idempotencyConflict() {
        return problem(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_CONFLICT",
                "Idempotency key conflict",
                "The key is already associated with a different command."
        );
    }

    @ExceptionHandler(ReplayInvalidStateException.class)
    ProblemDetail invalidState() {
        return problem(
                HttpStatus.CONFLICT,
                "INVALID_STATE_TRANSITION",
                "Invalid state transition",
                "Only an exhausted delivery may be replayed."
        );
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
        return invalidRequest();
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
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
