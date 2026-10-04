package com.farfartaxi.backend.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.farfartaxi.backend.service.FixedWindowLimiter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FixedWindowLimiterTest {
    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    @Test
    void fullLimiterNeverResetsExistingKeysAndRejectsNewOnes() {
        MutableClock clock = new MutableClock();
        FixedWindowLimiter l = new FixedWindowLimiter(clock, 2, Duration.ofHours(1), 2);
        assertThat(l.acquire("a")).isZero();
        assertThat(l.acquire("a")).isZero();
        assertThat(l.acquire("a")).isPositive(); // a is at its limit
        assertThat(l.acquire("b")).isZero();     // now full (a, b)
        assertThat(l.acquire("c")).isPositive(); // new key fails closed
        l.hit("d");
        assertThat(l.acquire("a")).isPositive(); // a still counted, never cleared
        assertThat(l.acquire("b")).isZero();
        assertThat(l.acquire("b")).isPositive();
        assertThat(l.acquire("c")).isPositive();
    }

    @Test
    void expiredWindowsArePurgedBeforeFailingClosed() {
        MutableClock clock = new MutableClock();
        FixedWindowLimiter l = new FixedWindowLimiter(clock, 1, Duration.ofHours(1), 2);
        l.acquire("a");
        l.acquire("b");
        assertThat(l.acquire("c")).isPositive();
        clock.now = clock.now.plus(Duration.ofHours(1));
        assertThat(l.acquire("c")).isZero();
    }
}
