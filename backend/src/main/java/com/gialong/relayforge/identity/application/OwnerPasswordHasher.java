package com.gialong.relayforge.identity.application;

public interface OwnerPasswordHasher {

    String hash(char[] plaintextPassword);
}
