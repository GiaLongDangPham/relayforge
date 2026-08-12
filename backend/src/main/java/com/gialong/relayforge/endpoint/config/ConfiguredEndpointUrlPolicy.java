package com.gialong.relayforge.endpoint.config;

import com.gialong.relayforge.endpoint.application.EndpointUrlPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Objects;

/**
 * Configuration-time URL validation. Attempt-time resolution and connection pinning remain a delivery concern.
 */
@Component
final class ConfiguredEndpointUrlPolicy implements EndpointUrlPolicy {

    private static final int MAX_LENGTH = 2048;

    private final boolean production;
    private final boolean allowLocalHttp;

    ConfiguredEndpointUrlPolicy(
            @Value("${relayforge.security.production:false}") boolean production,
            @Value("${relayforge.endpoint.allow-local-http:false}") boolean allowLocalHttp
    ) {
        if (production && allowLocalHttp) {
            throw new IllegalArgumentException("local HTTP must not be enabled in production");
        }
        this.production = production;
        this.allowLocalHttp = allowLocalHttp;
    }

    @Override
    public String requireValid(String destinationUrl) {
        String value = Objects.requireNonNull(destinationUrl, "destinationUrl must not be null");
        if (value.isBlank() || value.length() > MAX_LENGTH || !value.equals(value.strip())) {
            throw new IllegalArgumentException("destinationUrl must be trimmed and at most " + MAX_LENGTH + " characters");
        }

        URI parsed;
        try {
            parsed = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("destinationUrl must be a valid absolute URL", exception);
        }
        String scheme = parsed.getScheme();
        if (!parsed.isAbsolute() || parsed.getHost() == null || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("destinationUrl must have a host and no user-info or fragment");
        }
        if (parsed.getPort() < -1 || parsed.getPort() > 65535) {
            throw new IllegalArgumentException("destinationUrl has an invalid port");
        }
        if ("https".equalsIgnoreCase(scheme)) {
            return value;
        }
        if (!"http".equalsIgnoreCase(scheme) || production || !allowLocalHttp || !isLocalHost(parsed.getHost())) {
            throw new IllegalArgumentException("destinationUrl must use HTTPS outside explicit development local HTTP");
        }
        return value;
    }

    private static boolean isLocalHost(String host) {
        return host.equalsIgnoreCase("localhost")
                || host.equals("127.0.0.1")
                || host.equals("::1")
                || host.equals("[::1]");
    }
}
