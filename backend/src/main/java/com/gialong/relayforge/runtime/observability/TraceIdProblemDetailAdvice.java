package com.gialong.relayforge.runtime.observability;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/** Adds the API request correlation identifier to every controller-produced Problem Detail. */
@ControllerAdvice
@ConditionalOnProperty(prefix = "relayforge", name = "runtime", havingValue = "api")
final class TraceIdProblemDetailAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(
            MethodParameter returnType,
            Class<? extends HttpMessageConverter<?>> converterType
    ) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            org.springframework.http.server.ServerHttpRequest request,
            org.springframework.http.server.ServerHttpResponse response
    ) {
        if (body instanceof ProblemDetail problemDetail) {
            TraceIdContext.current().ifPresent(traceId -> problemDetail.setProperty("traceId", traceId));
        }
        return body;
    }
}
