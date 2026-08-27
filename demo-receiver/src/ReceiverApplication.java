import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;

/**
 * Local-only outbound webhook receiver used by the Docker Compose demo.
 * It deliberately has no RelayForge business dependency and retains only a bounded in-memory history.
 */
public final class ReceiverApplication {

    private static final int MAX_BODY_BYTES = 128 * 1024;
    private static final int MAX_OBSERVATIONS = 100;
    private static final String SIGNING_PREFIX = "whsec_";

    private final SecretState secretState = new SecretState();
    private final ObservationStore observations = new ObservationStore();
    private final long slowDelayMillis;

    private ReceiverApplication(long slowDelayMillis) {
        this.slowDelayMillis = slowDelayMillis;
    }

    public static void main(String[] args) throws IOException {
        int port = integerEnvironment("RECEIVER_PORT", 8081, 1, 65_535);
        long slowDelayMillis = integerEnvironment("RECEIVER_SLOW_DELAY_MILLIS", 12_000, 1, 60_000);
        ReceiverApplication application = new ReceiverApplication(slowDelayMillis);
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/health", application::health);
        server.createContext("/config/signing-secret", application::configureSigningSecret);
        server.createContext("/requests", application::listRequests);
        server.createContext("/webhooks/success", exchange -> application.receive(exchange, ResponseMode.SUCCESS));
        server.createContext("/webhooks/fail", exchange -> application.receive(exchange, ResponseMode.FAIL));
        server.createContext("/webhooks/slow", exchange -> application.receive(exchange, ResponseMode.SLOW));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            application.secretState.clear();
            server.stop(0);
        }, "receiver-shutdown"));
        server.start();
        System.out.printf("RelayForge demo receiver listening on port %d%n", port);
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        respond(exchange, 200, "application/json", "{\"status\":\"UP\"}");
    }

    private void configureSigningSecret(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equals("DELETE")) {
            secretState.clear();
            respondEmpty(exchange, 204);
            return;
        }
        if (!requireMethod(exchange, "PUT")) {
            return;
        }
        byte[] body = readBounded(exchange.getRequestBody());
        try {
            String rawSecret = new String(body, StandardCharsets.UTF_8).strip();
            secretState.configure(rawSecret);
            respondEmpty(exchange, 204);
        } catch (IllegalArgumentException exception) {
            respond(exchange, 400, "application/json", "{\"error\":\"invalid signing secret\"}");
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private void listRequests(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        respond(exchange, 200, "application/json", observations.asJson());
    }

    private void receive(HttpExchange exchange, ResponseMode mode) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        byte[] body;
        try {
            body = readBounded(exchange.getRequestBody());
        } catch (BodyTooLargeException exception) {
            respond(exchange, 413, "application/json", "{\"accepted\":false,\"reason\":\"body too large\"}");
            return;
        }

        Boolean signatureValid = secretState.verify(exchange.getRequestHeaders(), body);
        observations.add(Observation.from(exchange, mode, signatureValid, body));
        try {
            if (Boolean.FALSE.equals(signatureValid)) {
                respond(exchange, 401, "application/json", "{\"accepted\":false,\"reason\":\"invalid signature\"}");
                return;
            }
            if (mode == ResponseMode.SLOW) {
                try {
                    Thread.sleep(slowDelayMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    respond(exchange, 503, "application/json", "{\"accepted\":false,\"reason\":\"interrupted\"}");
                    return;
                }
            }
            if (mode == ResponseMode.FAIL) {
                respond(exchange, 500, "application/json", "{\"accepted\":false,\"mode\":\"fail\"}");
                return;
            }
            respond(exchange, 200, "application/json", "{\"accepted\":true}");
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static byte[] readBounded(InputStream input) throws IOException {
        byte[] value = input.readNBytes(MAX_BODY_BYTES + 1);
        if (value.length > MAX_BODY_BYTES) {
            Arrays.fill(value, (byte) 0);
            throw new BodyTooLargeException();
        }
        return value;
    }

    private static boolean requireMethod(HttpExchange exchange, String expected) throws IOException {
        if (exchange.getRequestMethod().equals(expected)) {
            return true;
        }
        exchange.getResponseHeaders().set("Allow", expected);
        respond(exchange, 405, "application/json", "{\"error\":\"method not allowed\"}");
        return false;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String response) throws IOException {
        byte[] body = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void respondEmpty(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static int integerEnvironment(String name, int fallback, int minimum, int maximum) {
        String configured = System.getenv(name);
        int value = configured == null || configured.isBlank() ? fallback : Integer.parseInt(configured);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
        return value;
    }

    private enum ResponseMode {
        SUCCESS,
        FAIL,
        SLOW
    }

    private static final class SecretState {

        private byte[] secret;

        synchronized void configure(String rawSecret) {
            if (rawSecret == null || !rawSecret.startsWith(SIGNING_PREFIX)) {
                throw new IllegalArgumentException("secret prefix is invalid");
            }
            byte[] decoded;
            try {
                decoded = Base64.getUrlDecoder().decode(rawSecret.substring(SIGNING_PREFIX.length()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("secret encoding is invalid", exception);
            }
            if (decoded.length != 32) {
                Arrays.fill(decoded, (byte) 0);
                throw new IllegalArgumentException("secret must contain 32 bytes");
            }
            clear();
            secret = decoded;
        }

        synchronized Boolean verify(Headers headers, byte[] body) {
            if (secret == null) {
                return null;
            }
            byte[] secretCopy = Arrays.copyOf(secret, secret.length);
            try {
                String timestamp = headers.getFirst("X-RelayForge-Timestamp");
                String eventId = headers.getFirst("X-RelayForge-Event-Id");
                String deliveryId = headers.getFirst("X-RelayForge-Delivery-Id");
                String attemptId = headers.getFirst("X-RelayForge-Attempt-Id");
                String supplied = headers.getFirst("X-RelayForge-Signature");
                if (timestamp == null || eventId == null || deliveryId == null || attemptId == null
                        || supplied == null || !supplied.startsWith("v1=")) {
                    return false;
                }
                byte[] digest = sha256(body);
                byte[] canonical = ("v1\n" + timestamp + "\n" + eventId + "\n" + deliveryId + "\n"
                        + attemptId + "\n" + HexFormat.of().formatHex(digest)).getBytes(StandardCharsets.UTF_8);
                byte[] expected = hmacSha256(secretCopy, canonical);
                byte[] actual = null;
                try {
                    actual = Base64.getUrlDecoder().decode(supplied.substring(3));
                    return MessageDigest.isEqual(expected, actual);
                } catch (IllegalArgumentException exception) {
                    return false;
                } finally {
                    Arrays.fill(digest, (byte) 0);
                    Arrays.fill(canonical, (byte) 0);
                    Arrays.fill(expected, (byte) 0);
                    if (actual != null) {
                        Arrays.fill(actual, (byte) 0);
                    }
                }
            } finally {
                Arrays.fill(secretCopy, (byte) 0);
            }
        }

        synchronized void clear() {
            if (secret != null) {
                Arrays.fill(secret, (byte) 0);
                secret = null;
            }
        }

        private static byte[] sha256(byte[] body) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(body);
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 must be available", exception);
            }
        }

        private static byte[] hmacSha256(byte[] secret, byte[] canonical) {
            try {
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(secret, "HmacSHA256"));
                return mac.doFinal(canonical);
            } catch (GeneralSecurityException exception) {
                throw new IllegalStateException("HMAC-SHA-256 must be available", exception);
            }
        }
    }

    private static final class ObservationStore {

        private final Deque<Observation> values = new ArrayDeque<>();

        synchronized void add(Observation observation) {
            while (values.size() >= MAX_OBSERVATIONS) {
                values.removeFirst();
            }
            values.addLast(observation);
        }

        synchronized String asJson() {
            List<String> encoded = new ArrayList<>(values.size());
            for (Observation observation : values) {
                encoded.add(observation.asJson());
            }
            return "[" + String.join(",", encoded) + "]";
        }
    }

    private record Observation(
            Instant receivedAt,
            String mode,
            Boolean signatureValid,
            String eventId,
            String deliveryId,
            String attemptId,
            String attemptNumber,
            String body
    ) {

        static Observation from(HttpExchange exchange, ResponseMode mode, Boolean signatureValid, byte[] body) {
            Headers headers = exchange.getRequestHeaders();
            return new Observation(
                    Instant.now(),
                    mode.name().toLowerCase(Locale.ROOT),
                    signatureValid,
                    headers.getFirst("X-RelayForge-Event-Id"),
                    headers.getFirst("X-RelayForge-Delivery-Id"),
                    headers.getFirst("X-RelayForge-Attempt-Id"),
                    headers.getFirst("X-RelayForge-Attempt-Number"),
                    new String(body, StandardCharsets.UTF_8)
            );
        }

        String asJson() {
            return "{" +
                    "\"receivedAt\":" + json(receivedAt.toString()) + "," +
                    "\"mode\":" + json(mode) + "," +
                    "\"signatureValid\":" + (signatureValid == null ? "null" : signatureValid) + "," +
                    "\"eventId\":" + json(eventId) + "," +
                    "\"deliveryId\":" + json(deliveryId) + "," +
                    "\"attemptId\":" + json(attemptId) + "," +
                    "\"attemptNumber\":" + json(attemptNumber) + "," +
                    "\"body\":" + json(body) +
                    "}";
        }

        private static String json(String value) {
            if (value == null) {
                return "null";
            }
            StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '"' -> escaped.append("\\\"");
                    case '\\' -> escaped.append("\\\\");
                    case '\b' -> escaped.append("\\b");
                    case '\f' -> escaped.append("\\f");
                    case '\n' -> escaped.append("\\n");
                    case '\r' -> escaped.append("\\r");
                    case '\t' -> escaped.append("\\t");
                    default -> {
                        if (character < 0x20) {
                            escaped.append(String.format("\\u%04x", (int) character));
                        } else {
                            escaped.append(character);
                        }
                    }
                }
            }
            return escaped.append('"').toString();
        }
    }

    private static final class BodyTooLargeException extends IOException {
    }
}
