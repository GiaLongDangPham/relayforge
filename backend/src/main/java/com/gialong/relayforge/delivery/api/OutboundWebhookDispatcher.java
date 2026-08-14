package com.gialong.relayforge.delivery.api;

/**
 * Worker-only technical port for one outbound request. It never claims work or mutates delivery persistence.
 */
public interface OutboundWebhookDispatcher {

    DispatchObservation dispatch(DispatchInstruction instruction);
}
