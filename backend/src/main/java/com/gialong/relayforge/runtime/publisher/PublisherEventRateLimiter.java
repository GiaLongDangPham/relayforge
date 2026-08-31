package com.gialong.relayforge.runtime.publisher;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Bounded per-API-process admission control for authenticated publisher event requests.
 */
public final class PublisherEventRateLimiter {

    static final int CAPACITY = 60;
    static final int REFILL_TOKENS_PER_SECOND = 30;
    static final long IDLE_EXPIRY_NANOS = 15L * 60L * 1_000_000_000L;
    static final int MAX_BUCKETS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(PublisherEventRateLimiter.class);
    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long MAX_REFILL_ELAPSED_NANOS = (long) CAPACITY * NANOS_PER_SECOND / REFILL_TOKENS_PER_SECOND;

    private final LongSupplier nanoTime;
    private final Map<UUID, Bucket> buckets = new LinkedHashMap<>(16, 0.75F, true);
    private final Counter admitted;
    private final Counter rejected;

    public PublisherEventRateLimiter(MeterRegistry meterRegistry) {
        this(System::nanoTime, meterRegistry);
    }

    PublisherEventRateLimiter(LongSupplier nanoTime, MeterRegistry meterRegistry) {
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime must not be null");
        MeterRegistry requiredRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.admitted = Counter.builder("relayforge.publisher.rate_limit.requests")
                .tag("outcome", "admitted")
                .description("Publisher event requests admitted by the local rate limiter")
                .register(requiredRegistry);
        this.rejected = Counter.builder("relayforge.publisher.rate_limit.requests")
                .tag("outcome", "rejected")
                .description("Publisher event requests rejected by the local rate limiter")
                .register(requiredRegistry);
    }

    public synchronized PublisherRateLimitDecision admit(UUID projectId) {
        UUID requiredProjectId = Objects.requireNonNull(projectId, "projectId must not be null");
        long now = nanoTime.getAsLong();
        evictExpiredBuckets(now);
        Bucket bucket = buckets.get(requiredProjectId);
        if (bucket == null) {
            evictOldestBucketIfFull();
            bucket = new Bucket(CAPACITY, now);
            buckets.put(requiredProjectId, bucket);
        }

        refill(bucket, now);
        bucket.lastAccessNanos = now;
        if (bucket.tokens > 0) {
            bucket.tokens--;
            admitted.increment();
            return PublisherRateLimitDecision.permit();
        }

        long retryAfterSeconds = retryAfterSeconds(bucket);
        rejected.increment();
        log.atDebug()
                .addKeyValue("event", "publisher_rate_limit_rejected")
                .addKeyValue("runtimeMode", "api")
                .log("Publisher event request rejected by local rate limiter");
        return PublisherRateLimitDecision.reject(retryAfterSeconds);
    }

    synchronized int retainedBucketCount() {
        return buckets.size();
    }

    private void refill(Bucket bucket, long now) {
        long elapsedNanos = Math.min(elapsedSince(bucket.lastRefillNanos, now), MAX_REFILL_ELAPSED_NANOS);
        long tokenNanos = elapsedNanos * REFILL_TOKENS_PER_SECOND + bucket.refillRemainder;
        long replenished = tokenNanos / NANOS_PER_SECOND;
        bucket.refillRemainder = tokenNanos % NANOS_PER_SECOND;
        bucket.lastRefillNanos = now;
        if (replenished == 0) {
            return;
        }

        bucket.tokens = (int) Math.min(CAPACITY, bucket.tokens + replenished);
        if (bucket.tokens == CAPACITY) {
            bucket.refillRemainder = 0;
        }
    }

    private static long retryAfterSeconds(Bucket bucket) {
        long missingCredit = NANOS_PER_SECOND - bucket.refillRemainder;
        long nanosUntilNextToken = divideCeiling(missingCredit, REFILL_TOKENS_PER_SECOND);
        return Math.max(1, divideCeiling(nanosUntilNextToken, NANOS_PER_SECOND));
    }

    private static long divideCeiling(long numerator, long divisor) {
        return (numerator + divisor - 1) / divisor;
    }

    private static long elapsedSince(long earlierNanos, long laterNanos) {
        long elapsed = laterNanos - earlierNanos;
        return Math.max(0, elapsed);
    }

    private void evictOldestBucketIfFull() {
        if (buckets.size() < MAX_BUCKETS) {
            return;
        }
        Iterator<UUID> iterator = buckets.keySet().iterator();
        iterator.next();
        iterator.remove();
    }

    private void evictExpiredBuckets(long now) {
        Iterator<Map.Entry<UUID, Bucket>> iterator = buckets.entrySet().iterator();
        while (iterator.hasNext()) {
            Bucket bucket = iterator.next().getValue();
            if (elapsedSince(bucket.lastAccessNanos, now) < IDLE_EXPIRY_NANOS) {
                return;
            }
            iterator.remove();
        }
    }

    private static final class Bucket {

        private int tokens;
        private long lastRefillNanos;
        private long refillRemainder;
        private long lastAccessNanos;

        private Bucket(int tokens, long now) {
            this.tokens = tokens;
            this.lastRefillNanos = now;
            this.lastAccessNanos = now;
        }
    }
}
