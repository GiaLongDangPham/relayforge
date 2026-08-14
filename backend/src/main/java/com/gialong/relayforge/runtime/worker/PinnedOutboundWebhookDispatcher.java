package com.gialong.relayforge.runtime.worker;

import com.gialong.relayforge.delivery.api.DispatchInstruction;
import com.gialong.relayforge.delivery.api.DispatchObservation;
import com.gialong.relayforge.delivery.api.OutboundWebhookDispatcher;
import com.gialong.relayforge.delivery.api.OutboundWebhookMessageSigner;
import com.gialong.relayforge.delivery.api.SignedOutboundWebhookMessage;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Worker-only Apache HTTP adapter. It performs no database work and makes one fresh pinned connection per dispatch.
 */
public final class PinnedOutboundWebhookDispatcher implements OutboundWebhookDispatcher, AutoCloseable {

    private final OutboundWebhookMessageSigner messageSigner;
    private final AttemptDestinationResolver destinationResolver;
    private final OutboundDispatchProperties properties;
    private final ExecutorService resolutionExecutor;

    public PinnedOutboundWebhookDispatcher(
            OutboundWebhookMessageSigner messageSigner,
            boolean production,
            boolean allowLocalHttp,
            OutboundDispatchProperties properties
    ) {
        this(
                messageSigner,
                new SystemHostAddressResolver(),
                new DestinationAddressPolicy(),
                Executors.newVirtualThreadPerTaskExecutor(),
                production,
                allowLocalHttp,
                properties
        );
    }

    PinnedOutboundWebhookDispatcher(
            OutboundWebhookMessageSigner messageSigner,
            HostAddressResolver hostAddressResolver,
            DestinationAddressPolicy addressPolicy,
            ExecutorService resolutionExecutor,
            boolean production,
            boolean allowLocalHttp,
            OutboundDispatchProperties properties
    ) {
        this.messageSigner = Objects.requireNonNull(messageSigner, "messageSigner must not be null");
        this.resolutionExecutor = Objects.requireNonNull(resolutionExecutor, "resolutionExecutor must not be null");
        this.destinationResolver = new AttemptDestinationResolver(
                Objects.requireNonNull(hostAddressResolver, "hostAddressResolver must not be null"),
                Objects.requireNonNull(addressPolicy, "addressPolicy must not be null"),
                this.resolutionExecutor,
                production,
                allowLocalHttp
        );
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
    }

    @Override
    public DispatchObservation dispatch(DispatchInstruction instruction) {
        DispatchInstruction requiredInstruction = Objects.requireNonNull(instruction, "instruction must not be null");
        DispatchDeadline deadline = new DispatchDeadline(properties.dispatchDeadline());
        PinnedDestination destination;
        try {
            destination = destinationResolver.resolve(requiredInstruction.destinationUrl(), deadline);
        } catch (DestinationResolutionException exception) {
            return DispatchObservation.failure(exception.outcome(), exception.failureCode(), deadline.elapsed());
        }

        SignedOutboundWebhookMessage message;
        try {
            message = messageSigner.sign(requiredInstruction, Instant.now());
        } catch (RuntimeException exception) {
            return DispatchObservation.failure(
                    DispatchObservation.Outcome.PERMANENT_FAILURE,
                    DispatchObservation.FailureCode.SIGNING_FAILURE,
                    deadline.elapsed()
            );
        }
        try (message) {
            return send(destination, message, deadline);
        } catch (DestinationResolutionException exception) {
            return DispatchObservation.failure(exception.outcome(), exception.failureCode(), deadline.elapsed());
        }
    }

    private DispatchObservation send(
            PinnedDestination destination,
            SignedOutboundWebhookMessage message,
            DispatchDeadline deadline
    ) {
        Duration remaining = deadline.remaining();
        Duration connectionTimeout = min(properties.connectionTimeout(), remaining);
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.of(connectionTimeout))
                .setResponseTimeout(Timeout.of(remaining))
                .setHardCancellationEnabled(true)
                .setRedirectsEnabled(false)
                .build();
        ConnectionConfig connectionConfig = ConnectionConfig.custom()
                .setConnectTimeout(Timeout.of(connectionTimeout))
                .setSocketTimeout(Timeout.of(remaining))
                .build();
        PoolingHttpClientConnectionManager connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(new PinnedDnsResolver(destination))
                .setDefaultConnectionConfig(connectionConfig)
                .setMaxConnTotal(1)
                .setMaxConnPerRoute(1)
                .build();

        CloseableHttpClient client = HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setConnectionReuseStrategy((request, response, context) -> false)
                .disableAutomaticRetries()
                .disableCookieManagement()
                .disableContentCompression()
                .disableRedirectHandling()
                .build();
        try {
            return executeWithinDeadline(client, destination, message, requestConfig, deadline);
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
                // The request is already complete or cancelled; close errors carry no receiver outcome.
            }
            connectionManager.close();
        }
    }

    private DispatchObservation executeWithinDeadline(
            CloseableHttpClient client,
            PinnedDestination destination,
            SignedOutboundWebhookMessage message,
            RequestConfig requestConfig,
            DispatchDeadline deadline
    ) {
        HttpPost request = new HttpPost(destination.uri());
        request.setConfig(requestConfig);
        message.headers().forEach(request::setHeader);
        request.setHeader("Connection", "close");
        Future<DispatchObservation> task = resolutionExecutor.submit(() -> execute(client, request, message, deadline));
        try {
            return task.get(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            request.cancel();
            task.cancel(true);
            return timeout(deadline);
        } catch (InterruptedException exception) {
            request.cancel();
            task.cancel(true);
            Thread.currentThread().interrupt();
            return networkFailure(deadline);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SocketTimeoutException) {
                return timeout(deadline);
            }
            if (cause instanceof IOException) {
                return networkFailure(deadline);
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("outbound HTTP task failed unexpectedly", cause);
        }
    }

    private DispatchObservation execute(
            CloseableHttpClient client,
            HttpPost request,
            SignedOutboundWebhookMessage message,
            DispatchDeadline deadline
    ) throws IOException {
        byte[] body = message.body();
        try {
            request.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
            try (var response = client.execute(request)) {
                ResponsePreview preview = readPreview(response.getEntity());
                return DispatchObservation.httpResponse(
                        classify(response.getCode()),
                        response.getCode(),
                        deadline.elapsed(),
                        preview.bytes(),
                        preview.truncated()
                );
            }
        } finally {
            Arrays.fill(body, (byte) 0);
        }
    }

    private static DispatchObservation timeout(DispatchDeadline deadline) {
        return DispatchObservation.failure(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                DispatchObservation.FailureCode.DISPATCH_TIMEOUT,
                deadline.elapsed()
        );
    }

    private static DispatchObservation networkFailure(DispatchDeadline deadline) {
        return DispatchObservation.failure(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                DispatchObservation.FailureCode.NETWORK_FAILURE,
                deadline.elapsed()
        );
    }

    private ResponsePreview readPreview(HttpEntity entity) {
        if (entity == null || properties.responsePreviewBytes() == 0) {
            return ResponsePreview.EMPTY;
        }
        ByteArrayOutputStream preview = new ByteArrayOutputStream(properties.responsePreviewBytes());
        try (InputStream content = entity.getContent()) {
            while (preview.size() < properties.responsePreviewBytes()) {
                int next = content.read();
                if (next < 0) {
                    return new ResponsePreview(preview.toByteArray(), false);
                }
                preview.write(next);
            }
            return new ResponsePreview(preview.toByteArray(), content.read() >= 0);
        } catch (SocketTimeoutException exception) {
            return new ResponsePreview(preview.toByteArray(), true);
        } catch (IOException exception) {
            return new ResponsePreview(preview.toByteArray(), true);
        }
    }

    private static DispatchObservation.Outcome classify(int httpStatus) {
        if (httpStatus >= 200 && httpStatus < 300) {
            return DispatchObservation.Outcome.SUCCEEDED;
        }
        if (httpStatus == 408 || httpStatus == 429 || httpStatus >= 500) {
            return DispatchObservation.Outcome.RETRYABLE_FAILURE;
        }
        return DispatchObservation.Outcome.PERMANENT_FAILURE;
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    @Override
    public void close() {
        resolutionExecutor.close();
    }

    private record ResponsePreview(byte[] bytes, boolean truncated) {

        private static final ResponsePreview EMPTY = new ResponsePreview(new byte[0], false);
    }
}
