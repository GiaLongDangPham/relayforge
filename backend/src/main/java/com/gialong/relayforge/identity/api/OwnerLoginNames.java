package com.gialong.relayforge.identity.api;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

public final class OwnerLoginNames {

    private static final int MAX_LOGIN_LENGTH = 100;
    private static final Pattern CANONICAL_LOGIN = Pattern.compile("[a-z0-9][a-z0-9._-]*");

    private OwnerLoginNames() {
    }

    public static String requireCanonical(String loginName) {
        if (loginName == null) {
            throw new IllegalArgumentException("loginName must not be null");
        }
        return canonicalize(loginName).orElseThrow(
                () -> new IllegalArgumentException("loginName must use the canonical owner login format")
        );
    }

    public static Optional<String> canonicalize(String loginName) {
        if (loginName == null) {
            return Optional.empty();
        }

        String canonical = loginName.strip().toLowerCase(Locale.ROOT);
        if (canonical.length() > MAX_LOGIN_LENGTH || !CANONICAL_LOGIN.matcher(canonical).matches()) {
            return Optional.empty();
        }
        return Optional.of(canonical);
    }
}
