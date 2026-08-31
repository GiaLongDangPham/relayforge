package com.gialong.relayforge.runtime.worker;
import com.gialong.relayforge.delivery.api.processing.DispatchObservation;

import com.gialong.relayforge.delivery.api.processing.DispatchObservation;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Resolves all addresses before dispatch and returns one validated address that the HTTP client must use directly.
 */
final class AttemptDestinationResolver {

    private final HostAddressResolver hostAddressResolver;
    private final DestinationAddressPolicy addressPolicy;
    private final ExecutorService resolutionExecutor;
    private final boolean production;
    private final boolean allowLocalHttp;

    AttemptDestinationResolver(
            HostAddressResolver hostAddressResolver,
            DestinationAddressPolicy addressPolicy,
            ExecutorService resolutionExecutor,
            boolean production,
            boolean allowLocalHttp
    ) {
        this.hostAddressResolver = Objects.requireNonNull(hostAddressResolver, "hostAddressResolver must not be null");
        this.addressPolicy = Objects.requireNonNull(addressPolicy, "addressPolicy must not be null");
        this.resolutionExecutor = Objects.requireNonNull(resolutionExecutor, "resolutionExecutor must not be null");
        this.production = production;
        this.allowLocalHttp = allowLocalHttp;
    }

    PinnedDestination resolve(String destinationUrl, DispatchDeadline deadline) {
        URI uri = parse(destinationUrl);
        String host = uri.getHost();
        if (host == null || host.isBlank() || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw rejected();
        }
        boolean developmentLocalHttp = isDevelopmentLocalHttp(uri, host);
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !developmentLocalHttp) {
            throw rejected();
        }

        InetAddress[] addresses = resolveAll(host, deadline);
        if (addresses.length == 0) {
            throw resolutionFailed();
        }
        if (production && Arrays.stream(addresses).anyMatch(address -> !addressPolicy.isPublic(address))) {
            throw rejected();
        }
        if (developmentLocalHttp && Arrays.stream(addresses).anyMatch(address -> !addressPolicy.isLoopback(address))) {
            throw rejected();
        }
        return new PinnedDestination(uri, host, addresses[0]);
    }

    private InetAddress[] resolveAll(String host, DispatchDeadline deadline) {
        Future<InetAddress[]> future = resolutionExecutor.submit(() -> hostAddressResolver.resolve(host));
        try {
            return future.get(deadline.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new DestinationResolutionException(
                    DispatchObservation.Outcome.RETRYABLE_FAILURE,
                    DispatchObservation.FailureCode.DISPATCH_TIMEOUT
            );
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw resolutionFailed();
        } catch (ExecutionException exception) {
            throw resolutionFailed();
        }
    }

    private boolean isDevelopmentLocalHttp(URI uri, String host) {
        return "http".equalsIgnoreCase(uri.getScheme())
                && !production
                && allowLocalHttp
                && isConfiguredLocalHost(host);
    }

    private static URI parse(String destinationUrl) {
        try {
            URI uri = URI.create(Objects.requireNonNull(destinationUrl, "destinationUrl must not be null"));
            if (!uri.isAbsolute()) {
                throw rejected();
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw rejected();
        }
    }

    private static boolean isConfiguredLocalHost(String host) {
        return host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("::1");
    }

    private static DestinationResolutionException rejected() {
        return new DestinationResolutionException(
                DispatchObservation.Outcome.PERMANENT_FAILURE,
                DispatchObservation.FailureCode.DESTINATION_REJECTED
        );
    }

    private static DestinationResolutionException resolutionFailed() {
        return new DestinationResolutionException(
                DispatchObservation.Outcome.RETRYABLE_FAILURE,
                DispatchObservation.FailureCode.DESTINATION_RESOLUTION_FAILED
        );
    }
}
