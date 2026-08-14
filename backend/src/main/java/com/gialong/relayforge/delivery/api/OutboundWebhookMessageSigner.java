package com.gialong.relayforge.delivery.api;

import java.time.Instant;

/**
 * Creates the signed message for one already-started delivery attempt.
 */
public interface OutboundWebhookMessageSigner {

    SignedOutboundWebhookMessage sign(DispatchInstruction instruction, Instant timestamp);
}
