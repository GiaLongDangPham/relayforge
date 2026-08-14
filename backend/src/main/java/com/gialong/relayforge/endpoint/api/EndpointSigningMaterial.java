package com.gialong.relayforge.endpoint.api;

/**
 * Opaque endpoint-owned signing material carried only by an in-memory dispatch instruction.
 */
public interface EndpointSigningMaterial extends AutoCloseable {

    /**
     * Returns a caller-owned plaintext copy for one HMAC operation.
     */
    byte[] decryptForDispatch();

    @Override
    void close();
}
