package com.gialong.relayforge.runtime.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class OwnerLoginFailureLimiterTests {

    @Test
    void boundsFailuresPerCanonicalLoginAndSourceThenClearsOrExpiresThem() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-10T00:00:00Z"));
        OwnerLoginFailureLimiter limiter = new OwnerLoginFailureLimiter(clock);

        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.recordFailure("owner", "127.0.0.1");
        }
        assertThat(limiter.isRateLimited("owner", "127.0.0.1")).isTrue();
        assertThat(limiter.isRateLimited("owner", "127.0.0.2")).isFalse();

        limiter.clear("owner", "127.0.0.1");
        assertThat(limiter.isRateLimited("owner", "127.0.0.1")).isFalse();

        for (int attempt = 0; attempt < 5; attempt++) {
            limiter.recordFailure("owner", "127.0.0.1");
        }
        clock.advanceSeconds(60);
        assertThat(limiter.isRateLimited("owner", "127.0.0.1")).isFalse();
    }

    private static final class MutableClock extends Clock {

        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }

        private void advanceSeconds(long seconds) {
            current = current.plusSeconds(seconds);
        }
    }
}
