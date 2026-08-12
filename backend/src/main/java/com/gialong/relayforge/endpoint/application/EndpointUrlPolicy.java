package com.gialong.relayforge.endpoint.application;

/**
 * Validates endpoint configuration-time URL shape without claiming attempt-time SSRF safety.
 */
public interface EndpointUrlPolicy {

    String requireValid(String destinationUrl);
}
