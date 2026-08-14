package com.gialong.relayforge.runtime.worker;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Replaceable resolver boundary so address policy and connection pinning can be proven without live public DNS.
 */
interface HostAddressResolver {

    InetAddress[] resolve(String hostname) throws UnknownHostException;
}
