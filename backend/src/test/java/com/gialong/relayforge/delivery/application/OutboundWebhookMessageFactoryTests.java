package com.gialong.relayforge.delivery.application;
import com.gialong.relayforge.delivery.api.processing.DispatchInstruction;
import com.gialong.relayforge.delivery.api.processing.SignedOutboundWebhookMessage;

import com.gialong.relayforge.delivery.api.processing.DispatchInstruction;
import com.gialong.relayforge.delivery.api.processing.SignedOutboundWebhookMessage;
import com.gialong.relayforge.endpoint.api.EndpointAttemptSnapshot;
import com.gialong.relayforge.endpoint.api.EndpointSigningMaterial;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class OutboundWebhookMessageFactoryTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant TIMESTAMP = Instant.parse("2026-08-14T12:34:56Z");

    @Test
    void createsTheSpecifiedBodyHeadersAndKnownHmacVectorWithoutExposingSensitiveValues() {
        RecordingSigningMaterial material = new RecordingSigningMaterial(sequence(32));
        try (DispatchInstruction instruction = instruction(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                "55555555-5555-5555-5555-555555555555",
                "{\"invoiceId\":\"inv_123\",\"amount\":4200}",
                material
        ); SignedOutboundWebhookMessage message = new OutboundWebhookMessageFactory(JSON).sign(instruction, TIMESTAMP)) {
            assertThat(new String(message.body(), StandardCharsets.UTF_8)).isEqualTo(
                    "{\"eventId\":\"22222222-2222-2222-2222-222222222222\",\"eventType\":\"invoice.paid\","
                            + "\"acceptedAt\":\"2026-08-14T12:00:00Z\",\"data\":{\"invoiceId\":\"inv_123\",\"amount\":4200}}"
            );
            assertThat(message.headers()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "Content-Type", "application/json",
                    "User-Agent", "RelayForge/1",
                    "X-RelayForge-Event-Id", "22222222-2222-2222-2222-222222222222",
                    "X-RelayForge-Delivery-Id", "33333333-3333-3333-3333-333333333333",
                    "X-RelayForge-Attempt-Id", "44444444-4444-4444-4444-444444444444",
                    "X-RelayForge-Attempt-Number", "1",
                    "X-RelayForge-Timestamp", "1786710896",
                    "X-RelayForge-Signature", "v1=7xOghOZfELj0UF4w5e29j4DT8AFujRKPsRJO-YN5e10"
            ));
            assertThat(material.lastPlaintextCopy).containsOnly((byte) 0);
            assertThat(message.toString()).doesNotContain(
                    "https://receiver.example/webhooks?customer=secret",
                    "inv_123",
                    "7xOghOZfELj0UF4w5e29j4DT8AFujRKPsRJO-YN5e10"
            );
        }
    }

    @Test
    void bindsTheSignatureToTheBodyTimestampAndAllAttemptIdentities() {
        String baseSignature = signatureFor(
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                "{\"invoiceId\":\"inv_123\"}",
                TIMESTAMP
        );

        assertThat(signatureFor(
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                "{\"invoiceId\":\"inv_456\"}",
                TIMESTAMP
        )).isNotEqualTo(baseSignature);
        assertThat(signatureFor(
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                "{\"invoiceId\":\"inv_123\"}",
                TIMESTAMP.plusSeconds(1)
        )).isNotEqualTo(baseSignature);
        assertThat(signatureFor(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                "{\"invoiceId\":\"inv_123\"}",
                TIMESTAMP
        )).isNotEqualTo(baseSignature);
        assertThat(signatureFor(
                "22222222-2222-2222-2222-222222222222",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                "44444444-4444-4444-4444-444444444444",
                "{\"invoiceId\":\"inv_123\"}",
                TIMESTAMP
        )).isNotEqualTo(baseSignature);
        assertThat(signatureFor(
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333",
                "cccccccc-cccc-cccc-cccc-cccccccccccc",
                "{\"invoiceId\":\"inv_123\"}",
                TIMESTAMP
        )).isNotEqualTo(baseSignature);
    }

    @Test
    void closeMakesBodyAndHeadersUnavailable() {
        SignedOutboundWebhookMessage message;
        try (DispatchInstruction instruction = instruction(
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333",
                "44444444-4444-4444-4444-444444444444",
                "55555555-5555-5555-5555-555555555555",
                "{\"invoiceId\":\"inv_123\"}",
                new RecordingSigningMaterial(sequence(32))
        )) {
            message = new OutboundWebhookMessageFactory(JSON).sign(instruction, TIMESTAMP);
        }
        message.close();

        assertThatIllegalStateException().isThrownBy(message::body);
        assertThatIllegalStateException().isThrownBy(message::headers);
    }

    private static String signatureFor(
            String eventId,
            String deliveryId,
            String attemptId,
            String payload,
            Instant timestamp
    ) {
        try (DispatchInstruction instruction = instruction(
                "11111111-1111-1111-1111-111111111111",
                eventId,
                deliveryId,
                attemptId,
                "55555555-5555-5555-5555-555555555555",
                payload,
                new RecordingSigningMaterial(sequence(32))
        ); SignedOutboundWebhookMessage message = new OutboundWebhookMessageFactory(JSON).sign(instruction, timestamp)) {
            return message.headers().get("X-RelayForge-Signature");
        }
    }

    private static DispatchInstruction instruction(
            String projectId,
            String eventId,
            String deliveryId,
            String attemptId,
            String claimToken,
            String payload,
            EndpointSigningMaterial signingMaterial
    ) {
        UUID requiredProjectId = UUID.fromString(projectId);
        return new DispatchInstruction(
                requiredProjectId,
                UUID.fromString(eventId),
                UUID.fromString(deliveryId),
                UUID.fromString(attemptId),
                UUID.fromString(claimToken),
                1,
                "invoice.paid",
                Instant.parse("2026-08-14T12:00:00Z"),
                Instant.parse("2026-08-14T12:34:50Z"),
                Instant.parse("2026-08-14T12:35:10Z"),
                payload.getBytes(StandardCharsets.UTF_8),
                new EndpointAttemptSnapshot(
                        requiredProjectId,
                        UUID.fromString("66666666-6666-6666-6666-666666666666"),
                        "https://receiver.example/webhooks?customer=secret",
                        signingMaterial
                )
        );
    }

    private static byte[] sequence(int length) {
        byte[] result = new byte[length];
        for (int index = 0; index < length; index++) {
            result[index] = (byte) index;
        }
        return result;
    }

    private static final class RecordingSigningMaterial implements EndpointSigningMaterial {

        private final byte[] secret;
        private byte[] lastPlaintextCopy;

        private RecordingSigningMaterial(byte[] secret) {
            this.secret = Arrays.copyOf(secret, secret.length);
            Arrays.fill(secret, (byte) 0);
        }

        @Override
        public byte[] decryptForDispatch() {
            lastPlaintextCopy = Arrays.copyOf(secret, secret.length);
            return lastPlaintextCopy;
        }

        @Override
        public void close() {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
