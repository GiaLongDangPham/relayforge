package com.gialong.relayforge.identity.api;

public interface OwnerBootstrap {

    OwnerBootstrapResult bootstrap(String loginName, char[] plaintextPassword);
}
