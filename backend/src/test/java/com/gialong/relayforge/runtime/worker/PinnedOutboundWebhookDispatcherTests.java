package com.gialong.relayforge.runtime.worker;

import com.gialong.relayforge.delivery.api.DispatchInstruction;
import com.gialong.relayforge.delivery.api.DispatchObservation;
import com.gialong.relayforge.delivery.api.OutboundWebhookMessageSigner;
import com.gialong.relayforge.delivery.api.SignedOutboundWebhookMessage;
import com.gialong.relayforge.endpoint.api.EndpointAttemptSnapshot;
import com.gialong.relayforge.endpoint.api.EndpointSigningMaterial;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PinnedOutboundWebhookDispatcherTests {

    @Test
    void connectsToTheSelectedAddressAndSendsOneSignedMessage() throws Exception {
        AtomicReference<String> receivedBody = new AtomicReference<>();
        AtomicReference<String> eventId = new AtomicReference<>();
        AtomicReference<String> signature = new AtomicReference<>();
        HttpServer server = startOnSecondLoopback(exchange -> {
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            eventId.set(exchange.getRequestHeaders().getFirst("X-RelayForge-Event-Id"));
            signature.set(exchange.getRequestHeaders().getFirst("X-RelayForge-Signature"));
            respond(exchange, 200, "receiver-ok".getBytes(StandardCharsets.UTF_8));
        });
        AtomicInteger resolutions = new AtomicInteger();
        try (PinnedOutboundWebhookDispatcher dispatcher = dispatcher(
                host -> {
                    resolutions.incrementAndGet();
                    return new InetAddress[]{InetAddress.getByName("127.0.0.2")};
                },
                Duration.ofSeconds(2)
        ); DispatchInstruction instruction = instruction(destination(server, "/deliveries"))) {
            try (DispatchObservation observation = dispatcher.dispatch(instruction)) {
                assertThat(observation.outcome()).isEqualTo(DispatchObservation.Outcome.SUCCEEDED);
                assertThat(observation.httpStatus()).hasValue(200);
                assertThat(new String(observation.responsePreview(), StandardCharsets.UTF_8)).isEqualTo("receiver-ok");
            }
        } finally {
            server.stop(0);
        }

        assertThat(resolutions).hasValue(1);
        assertThat(receivedBody).hasValue("{\"signed\":true}");
        assertThat(eventId).hasValue("22222222-2222-2222-2222-222222222222");
        assertThat(signature).hasValue("v1=c2lnbmF0dXJl");
    }

    @Test
    void doesNotFollowRedirectsAndClassifiesTheOriginalThreeHundredResponseAsPermanent() throws Exception {
        AtomicInteger redirectedTargetCalls = new AtomicInteger();
        HttpServer server = startOnSecondLoopback(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/redirect")) {
                exchange.getResponseHeaders().set("Location", "/must-not-run");
                respond(exchange, 302, new byte[0]);
                return;
            }
            redirectedTargetCalls.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });
        try (PinnedOutboundWebhookDispatcher dispatcher = dispatcher(
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.2")},
                Duration.ofSeconds(2)
        ); DispatchInstruction instruction = instruction(destination(server, "/redirect"));
             DispatchObservation observation = dispatcher.dispatch(instruction)) {
            assertThat(observation.outcome()).isEqualTo(DispatchObservation.Outcome.PERMANENT_FAILURE);
            assertThat(observation.httpStatus()).hasValue(302);
        } finally {
            server.stop(0);
        }

        assertThat(redirectedTargetCalls).hasValue(0);
    }

    @Test
    void boundsResponsePreviewAndClassifiesFiveHundredAsRetryable() throws Exception {
        byte[] oversizedResponse = new byte[8 * 1024 + 1];
        Arrays.fill(oversizedResponse, (byte) 'x');
        HttpServer server = startOnSecondLoopback(exchange -> {
            exchange.getResponseHeaders().set("Retry-After", " \t45\t ");
            respond(exchange, 503, oversizedResponse);
        });
        try (PinnedOutboundWebhookDispatcher dispatcher = dispatcher(
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.2")},
                Duration.ofSeconds(2)
        ); DispatchInstruction instruction = instruction(destination(server, "/unavailable"));
             DispatchObservation observation = dispatcher.dispatch(instruction)) {
            assertThat(observation.outcome()).isEqualTo(DispatchObservation.Outcome.RETRYABLE_FAILURE);
            assertThat(observation.httpStatus()).hasValue(503);
            assertThat(observation.retryAfterDelay()).contains(Duration.ofSeconds(45));
            assertThat(observation.responsePreview()).hasSize(8 * 1024);
            assertThat(observation.responseTruncated()).isTrue();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void appliesTheOuterDeadlineToAReceiverThatDoesNotProduceResponseHeaders() throws Exception {
        HttpServer server = startOnSecondLoopback(exchange -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, new byte[0]);
        });
        try (PinnedOutboundWebhookDispatcher dispatcher = dispatcher(
                host -> new InetAddress[]{InetAddress.getByName("127.0.0.2")},
                Duration.ofMillis(100)
        ); DispatchInstruction instruction = instruction(destination(server, "/slow"));
             DispatchObservation observation = dispatcher.dispatch(instruction)) {
            assertThat(observation.outcome()).isEqualTo(DispatchObservation.Outcome.RETRYABLE_FAILURE);
            assertThat(observation.failureCode()).contains(DispatchObservation.FailureCode.DISPATCH_TIMEOUT);
        } finally {
            server.stop(0);
        }
    }

    private static PinnedOutboundWebhookDispatcher dispatcher(HostAddressResolver resolver, Duration deadline) {
        OutboundWebhookMessageSigner signer = (instruction, timestamp) -> new SignedOutboundWebhookMessage(
                instruction.eventId(),
                instruction.deliveryId(),
                instruction.attemptId(),
                instruction.attemptNumber(),
                timestamp.getEpochSecond(),
                "{\"signed\":true}".getBytes(StandardCharsets.UTF_8),
                "signature".getBytes(StandardCharsets.US_ASCII)
        );
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        return new PinnedOutboundWebhookDispatcher(
                signer,
                resolver,
                new DestinationAddressPolicy(),
                executor,
                false,
                true,
                new OutboundDispatchProperties(min(Duration.ofMillis(50), deadline), deadline, 8 * 1024)
        );
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static DispatchInstruction instruction(String destinationUrl) {
        UUID projectId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        return new DispatchInstruction(
                projectId,
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                UUID.fromString("55555555-5555-5555-5555-555555555555"),
                1,
                "invoice.paid",
                Instant.parse("2026-08-14T12:00:00Z"),
                Instant.parse("2026-08-14T12:34:50Z"),
                Instant.parse("2026-08-14T12:35:10Z"),
                "{\"invoiceId\":\"inv_123\"}".getBytes(StandardCharsets.UTF_8),
                new EndpointAttemptSnapshot(
                        projectId,
                        UUID.fromString("66666666-6666-6666-6666-666666666666"),
                        destinationUrl,
                        new FixedSigningMaterial()
                )
        );
    }

    private static HttpServer startOnSecondLoopback(HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.2"), 0), 0);
        server.createContext("/", handler);
        server.start();
        return server;
    }

    private static String destination(HttpServer server, String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static final class FixedSigningMaterial implements EndpointSigningMaterial {

        @Override
        public byte[] decryptForDispatch() {
            return new byte[32];
        }

        @Override
        public void close() {
        }
    }
}
