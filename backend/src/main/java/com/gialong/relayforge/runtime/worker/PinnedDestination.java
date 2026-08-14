package com.gialong.relayforge.runtime.worker;

import java.net.InetAddress;
import java.net.URI;
import java.util.Objects;

/**
 * One already-validated socket target plus its original HTTP target host.
 */
final class PinnedDestination {

    private final URI uri;
    private final String host;
    private final InetAddress selectedAddress;

    PinnedDestination(URI uri, String host, InetAddress selectedAddress) {
        this.uri = Objects.requireNonNull(uri, "uri must not be null");
        this.host = Objects.requireNonNull(host, "host must not be null");
        this.selectedAddress = Objects.requireNonNull(selectedAddress, "selectedAddress must not be null");
    }

    URI uri() {
        return uri;
    }

    String host() {
        return host;
    }

    InetAddress selectedAddress() {
        return selectedAddress;
    }

    @Override
    public String toString() {
        return "PinnedDestination[host=<redacted>, selectedAddress=<redacted>]";
    }
}
