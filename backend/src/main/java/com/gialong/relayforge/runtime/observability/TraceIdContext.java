package com.gialong.relayforge.runtime.observability;

import org.slf4j.MDC;

import java.util.Optional;

/** Holds only a validated request correlation identifier for the active servlet thread. */
public final class TraceIdContext {

    public static final String HEADER_NAME = "X-RelayForge-Trace-Id";
    public static final String MDC_KEY = "traceId";

    private TraceIdContext() {
    }

    public static Optional<String> current() {
        return Optional.ofNullable(MDC.get(MDC_KEY));
    }
}
