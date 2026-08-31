package com.gialong.relayforge.runtime.publisher;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class PublisherEventRateLimiterTests {

    @Test
    void sharesOneAtomicProjectBucketThenRefillsWithMonotonicTime() throws Exception {
        MutableNanoTime nanoTime = new MutableNanoTime();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            PublisherEventRateLimiter limiter = new PublisherEventRateLimiter(nanoTime, meters);
            UUID projectId = UUID.randomUUID();

            List<Future<PublisherRateLimitDecision>> admissions = new ArrayList<>();
            for (int request = 0; request < PublisherEventRateLimiter.CAPACITY + 20; request++) {
                admissions.add(executor.submit(() -> limiter.admit(projectId)));
            }

            long admitted = 0;
            for (Future<PublisherRateLimitDecision> admission : admissions) {
                if (admission.get().admitted()) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(PublisherEventRateLimiter.CAPACITY);
            assertThat(limiter.admit(projectId))
                    .returns(false, PublisherRateLimitDecision::admitted)
                    .returns(1L, PublisherRateLimitDecision::retryAfterSeconds);

            nanoTime.advanceNanos(34_000_000L);
            assertThat(limiter.admit(projectId).admitted()).isTrue();
            assertThat(meters.find("relayforge.publisher.rate_limit.requests").tag("outcome", "admitted").counter().count())
                    .isEqualTo(PublisherEventRateLimiter.CAPACITY + 1);
            assertThat(meters.find("relayforge.publisher.rate_limit.requests").tag("outcome", "rejected").counter().count())
                    .isEqualTo(21);
        } finally {
            meters.close();
        }
    }

    @Test
    void isolatesProjectsExpiresIdleBucketsAndBoundsRetainedState() {
        MutableNanoTime nanoTime = new MutableNanoTime();
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            PublisherEventRateLimiter limiter = new PublisherEventRateLimiter(nanoTime, meters);
            UUID firstProject = UUID.randomUUID();
            UUID secondProject = UUID.randomUUID();

            for (int request = 0; request < PublisherEventRateLimiter.CAPACITY; request++) {
                assertThat(limiter.admit(firstProject).admitted()).isTrue();
            }
            assertThat(limiter.admit(firstProject).admitted()).isFalse();
            assertThat(limiter.admit(secondProject).admitted()).isTrue();

            nanoTime.advanceNanos(PublisherEventRateLimiter.IDLE_EXPIRY_NANOS);
            assertThat(limiter.admit(UUID.randomUUID()).admitted()).isTrue();
            assertThat(limiter.retainedBucketCount()).isEqualTo(1);
            assertThat(limiter.admit(firstProject).admitted()).isTrue();

            for (int bucket = 0; bucket < PublisherEventRateLimiter.MAX_BUCKETS + 1; bucket++) {
                assertThat(limiter.admit(UUID.randomUUID()).admitted()).isTrue();
            }
            assertThat(limiter.retainedBucketCount()).isEqualTo(PublisherEventRateLimiter.MAX_BUCKETS);
        } finally {
            meters.close();
        }
    }

    private static final class MutableNanoTime implements LongSupplier {

        private long current;

        @Override
        public long getAsLong() {
            return current;
        }

        private void advanceNanos(long nanos) {
            current += nanos;
        }
    }
}
