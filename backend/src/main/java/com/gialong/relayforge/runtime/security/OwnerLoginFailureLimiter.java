package com.gialong.relayforge.runtime.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class OwnerLoginFailureLimiter {

    private static final int MAX_FAILURES = 5;
    private static final int MAX_BUCKETS = 10_000;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final Clock clock;
    private final Map<BucketKey, Bucket> buckets = new LinkedHashMap<>(16, 0.75F, true);

    public OwnerLoginFailureLimiter(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    public synchronized boolean isRateLimited(String canonicalLogin, String sourceIp) {
        BucketKey key = new BucketKey(canonicalLogin, sourceIp);
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            return false;
        }
        if (expired(bucket, clock.instant())) {
            buckets.remove(key);
            return false;
        }
        return bucket.failures >= MAX_FAILURES;
    }

    public synchronized void recordFailure(String canonicalLogin, String sourceIp) {
        BucketKey key = new BucketKey(canonicalLogin, sourceIp);
        Instant now = clock.instant();
        Bucket bucket = buckets.get(key);
        if (bucket == null || expired(bucket, now)) {
            evictOldestBucketIfFull();
            buckets.put(key, new Bucket(now, 1));
            return;
        }
        buckets.put(key, new Bucket(bucket.windowStartedAt, bucket.failures + 1));
    }

    public synchronized void clear(String canonicalLogin, String sourceIp) {
        buckets.remove(new BucketKey(canonicalLogin, sourceIp));
    }

    private boolean expired(Bucket bucket, Instant now) {
        return !now.isBefore(bucket.windowStartedAt.plus(WINDOW));
    }

    private void evictOldestBucketIfFull() {
        if (buckets.size() < MAX_BUCKETS) {
            return;
        }
        Iterator<BucketKey> iterator = buckets.keySet().iterator();
        iterator.next();
        iterator.remove();
    }

    private record BucketKey(String canonicalLogin, String sourceIp) {

        private BucketKey {
            Objects.requireNonNull(canonicalLogin, "canonicalLogin must not be null");
            Objects.requireNonNull(sourceIp, "sourceIp must not be null");
        }
    }

    private record Bucket(Instant windowStartedAt, int failures) {
    }
}
