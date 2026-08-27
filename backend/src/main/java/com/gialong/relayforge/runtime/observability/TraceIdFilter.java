package com.gialong.relayforge.runtime.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Uses only canonical UUID request identifiers so an arbitrary client header cannot inject or
 * create an unbounded log value. The identifier is echoed for API error correlation.
 */
public final class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = canonicalTraceId(request.getHeader(TraceIdContext.HEADER_NAME));
        MDC.put(TraceIdContext.MDC_KEY, traceId);
        response.setHeader(TraceIdContext.HEADER_NAME, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TraceIdContext.MDC_KEY);
        }
    }

    private static String canonicalTraceId(String candidate) {
        if (candidate != null) {
            try {
                return UUID.fromString(candidate).toString();
            } catch (IllegalArgumentException ignored) {
                // A fresh server identifier is safer than accepting arbitrary log content.
            }
        }
        return UUID.randomUUID().toString();
    }
}
