package com.gialong.relayforge.runtime.worker;

import java.net.InetAddress;
import java.net.UnknownHostException;

final class SystemHostAddressResolver implements HostAddressResolver {

    @Override
    public InetAddress[] resolve(String hostname) throws UnknownHostException {
        return InetAddress.getAllByName(hostname);
    }
}
