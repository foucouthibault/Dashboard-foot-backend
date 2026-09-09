package com.example.Dashboard_foot.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FootballDataRateLimiterTest {

    /** Horloge dont on peut avancer manuellement le temps pour tester la fenêtre glissante sans Thread.sleep. */
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void allowsUpToTheConfiguredLimitWithinTheWindow() {
        MutableClock clock = new MutableClock();
        FootballDataRateLimiter limiter = new FootballDataRateLimiter(3, Duration.ofMinutes(1), clock);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());
    }

    @Test
    void frees_up_a_slot_once_the_oldest_request_leaves_the_window() {
        MutableClock clock = new MutableClock();
        FootballDataRateLimiter limiter = new FootballDataRateLimiter(2, Duration.ofMinutes(1), clock);

        assertTrue(limiter.tryAcquire());
        clock.advance(Duration.ofSeconds(30));
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire());

        clock.advance(Duration.ofSeconds(31));
        assertTrue(limiter.tryAcquire(), "le premier slot doit s'être libéré après 61s");
    }

    @Test
    void secondsUntilNextSlot_reflectsRemainingWaitOnTheOldestEntry() {
        MutableClock clock = new MutableClock();
        FootballDataRateLimiter limiter = new FootballDataRateLimiter(1, Duration.ofMinutes(1), clock);

        assertTrue(limiter.tryAcquire());
        clock.advance(Duration.ofSeconds(15));
        assertFalse(limiter.tryAcquire());

        assertEquals(45, limiter.secondsUntilNextSlot());
    }

    @Test
    void secondsUntilNextSlot_isZeroWhenNoRequestIsTracked() {
        FootballDataRateLimiter limiter = new FootballDataRateLimiter(5, Duration.ofMinutes(1), new MutableClock());

        assertEquals(0, limiter.secondsUntilNextSlot());
    }
}
