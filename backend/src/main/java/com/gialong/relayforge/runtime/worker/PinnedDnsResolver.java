package com.gialong.relayforge.runtime.worker;

import org.apache.hc.client5.http.DnsResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Supplies the already-selected address to Apache HttpClient and never invokes DNS itself.
 */
final class PinnedDnsResolver implements DnsResolver {

    private final PinnedDestination destination;

    PinnedDnsResolver(PinnedDestination destination) {
        this.destination = Objects.requireNonNull(destination, "destination must not be null");
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        if (!destination.host().equalsIgnoreCase(host)) {
            throw new UnknownHostException("unexpected outbound dispatch host");
        }
        return new InetAddress[]{destination.selectedAddress()};
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        if (!destination.host().equalsIgnoreCase(host)) {
            throw new UnknownHostException("unexpected outbound dispatch host");
        }
        return destination.host();
    }
}
