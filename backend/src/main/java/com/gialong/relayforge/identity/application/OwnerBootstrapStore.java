package com.gialong.relayforge.identity.application;

import java.util.UUID;

public interface OwnerBootstrapStore {

    OwnerBootstrapRecord insertOrGet(UUID candidateId, String loginName, String passwordHash);
}
