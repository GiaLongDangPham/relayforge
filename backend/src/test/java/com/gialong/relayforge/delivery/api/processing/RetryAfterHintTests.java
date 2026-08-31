package com.gialong.relayforge.delivery.api.processing;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetryAfterHintTests {

    @Test
    void acceptsOneDeltaSecondsValueFor429And503IncludingOptionalWhitespaceAndZero() {
        assertThat(RetryAfterHint.parse(429, List.of(" \t45\t "))).contains(Duration.ofSeconds(45));
        assertThat(RetryAfterHint.parse(503, List.of("0"))).contains(Duration.ZERO);
    }

    @Test
    void rejectsMissingRepeatedMalformedAndHttpDateValues() {
        assertThat(RetryAfterHint.parse(429, List.of())).isEmpty();
        assertThat(RetryAfterHint.parse(429, List.of("10", "20"))).isEmpty();
        assertThat(RetryAfterHint.parse(429, List.of("-1"))).isEmpty();
        assertThat(RetryAfterHint.parse(429, List.of("+1"))).isEmpty();
        assertThat(RetryAfterHint.parse(429, List.of("1.5"))).isEmpty();
        assertThat(RetryAfterHint.parse(429, List.of("Wed, 21 Oct 2015 07:28:00 GMT"))).isEmpty();
        assertThat(RetryAfterHint.parse(429, List.of("１２"))).isEmpty();
    }

    @Test
    void ignoresOtherwiseValidHeaderForStatusesOutside429And503() {
        assertThat(RetryAfterHint.parse(408, List.of("30"))).isEmpty();
        assertThat(RetryAfterHint.parse(500, List.of("30"))).isEmpty();
        assertThat(RetryAfterHint.parse(200, List.of("30"))).isEmpty();
    }

    @Test
    void capsOversizedDecimalWithoutOverflow() {
        assertThat(RetryAfterHint.parse(503, List.of("301"))).contains(Duration.ofSeconds(300));
        assertThat(RetryAfterHint.parse(503, List.of("999999999999999999999999999999999999999999999999")))
                .contains(Duration.ofSeconds(300));
    }
}
