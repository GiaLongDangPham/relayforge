package com.gialong.relayforge.delivery.application;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class PublishCommandFingerprintTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void sortsObjectPropertiesButRetainsArrayOrderAndEventTypeInFingerprint() throws Exception {
        PublishCommandFingerprint fingerprint = new PublishCommandFingerprint(JSON);
        byte[] first = fingerprint.fingerprint("invoice.paid", JSON.readTree("{\"b\":2,\"a\":[1,2]}"));
        byte[] sameSemanticObject = fingerprint.fingerprint("invoice.paid", JSON.readTree("{\"a\":[1,2],\"b\":2}"));
        byte[] differentArrayOrder = fingerprint.fingerprint("invoice.paid", JSON.readTree("{\"a\":[2,1],\"b\":2}"));
        byte[] differentEventType = fingerprint.fingerprint("invoice.failed", JSON.readTree("{\"a\":[1,2],\"b\":2}"));
        try {
            assertThat(first).containsExactly(sameSemanticObject);
            assertThat(Arrays.equals(first, differentArrayOrder)).isFalse();
            assertThat(Arrays.equals(first, differentEventType)).isFalse();
        } finally {
            Arrays.fill(first, (byte) 0);
            Arrays.fill(sameSemanticObject, (byte) 0);
            Arrays.fill(differentArrayOrder, (byte) 0);
            Arrays.fill(differentEventType, (byte) 0);
        }
    }
}
