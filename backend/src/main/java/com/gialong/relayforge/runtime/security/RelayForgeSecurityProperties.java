package com.gialong.relayforge.runtime.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.util.Objects;

@ConfigurationProperties(prefix = "relayforge.security")
public final class RelayForgeSecurityProperties {

    private final String dashboardOrigin;
    private final boolean secureCookie;
    private final boolean production;

    public RelayForgeSecurityProperties(String dashboardOrigin, boolean secureCookie, boolean production) {
        this.dashboardOrigin = requireOrigin(dashboardOrigin);
        this.secureCookie = secureCookie;
        this.production = production;
        if (production && !secureCookie) {
            throw new IllegalArgumentException("relayforge.security.secure-cookie must be true in production");
        }
        if (production && !URI.create(this.dashboardOrigin).getScheme().equals("https")) {
            throw new IllegalArgumentException("relayforge.security.dashboard-origin must use HTTPS in production");
        }
    }

    public String dashboardOrigin() {
        return dashboardOrigin;
    }

    public boolean secureCookie() {
        return secureCookie;
    }

    public boolean production() {
        return production;
    }

    private static String requireOrigin(String dashboardOrigin) {
        String origin = Objects.requireNonNull(dashboardOrigin, "dashboardOrigin must not be null");
        URI parsed = URI.create(origin);
        if (!parsed.isAbsolute()
                || parsed.getHost() == null
                || parsed.getRawUserInfo() != null
                || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null
                || parsed.getRawPath() != null && !parsed.getRawPath().isEmpty() && !parsed.getRawPath().equals("/")) {
            throw new IllegalArgumentException("relayforge.security.dashboard-origin must be an origin without a path");
        }
        if (!parsed.getScheme().equals("http") && !parsed.getScheme().equals("https")) {
            throw new IllegalArgumentException("relayforge.security.dashboard-origin must use HTTP or HTTPS");
        }
        return origin.endsWith("/") ? origin.substring(0, origin.length() - 1) : origin;
    }
}
