package com.gialong.relayforge.runtime.worker;
import com.gialong.relayforge.delivery.api.processing.DispatchObservation;

import com.gialong.relayforge.delivery.api.processing.DispatchObservation;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptDestinationResolverTests {

    @Test
    void productionRejectsTheEntireDnsAnswerWhenAnyAddressIsProhibited() throws Exception {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AttemptDestinationResolver resolver = new AttemptDestinationResolver(
                    host -> new InetAddress[]{InetAddress.getByName("8.8.8.8"), InetAddress.getByName("10.0.0.1")},
                    new DestinationAddressPolicy(),
                    executor,
                    true,
                    false
            );

            assertThatThrownBy(() -> resolver.resolve(
                    "https://receiver.example/webhooks",
                    new DispatchDeadline(Duration.ofSeconds(1))
            )).isInstanceOfSatisfying(DestinationResolutionException.class, exception -> {
                assertThat(exception.outcome()).isEqualTo(DispatchObservation.Outcome.PERMANENT_FAILURE);
                assertThat(exception.failureCode()).isEqualTo(DispatchObservation.FailureCode.DESTINATION_REJECTED);
            });
        }
    }

    @Test
    void returnsOnePinnedAddressAfterExactlyOneResolution() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            AttemptDestinationResolver resolver = new AttemptDestinationResolver(
                    host -> {
                        calls.incrementAndGet();
                        return new InetAddress[]{InetAddress.getByName("8.8.8.8")};
                    },
                    new DestinationAddressPolicy(),
                    executor,
                    true,
                    false
            );

            PinnedDestination destination = resolver.resolve(
                    "https://receiver.example/webhooks",
                    new DispatchDeadline(Duration.ofSeconds(1))
            );

            assertThat(calls).hasValue(1);
            assertThat(destination.selectedAddress().getHostAddress()).isEqualTo("8.8.8.8");
            assertThat(new PinnedDnsResolver(destination).resolve("receiver.example"))
                    .extracting(InetAddress::getHostAddress)
                    .containsExactly("8.8.8.8");
        }
    }
}
