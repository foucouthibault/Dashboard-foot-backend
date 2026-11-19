package fr.foucouthibault.dashboardfoot.service;

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

    private static final QuotaCategory CURRENT = QuotaCategory.CURRENT;
    private static final QuotaCategory HISTORICAL = QuotaCategory.HISTORICAL;

    private static FootballDataRateLimiter limiter(int max, int historicalMax, Clock clock) {
        return new FootballDataRateLimiter(max, historicalMax, Duration.ofMinutes(1), clock);
    }

    @Test
    void allowsUpToTheConfiguredLimitWithinTheWindow() {
        FootballDataRateLimiter limiter = limiter(3, 3, new MutableClock());

        assertTrue(limiter.tryAcquire(CURRENT));
        assertTrue(limiter.tryAcquire(CURRENT));
        assertTrue(limiter.tryAcquire(CURRENT));
        assertFalse(limiter.tryAcquire(CURRENT));
    }

    @Test
    void frees_up_a_slot_once_the_oldest_request_leaves_the_window() {
        MutableClock clock = new MutableClock();
        FootballDataRateLimiter limiter = limiter(2, 2, clock);

        assertTrue(limiter.tryAcquire(CURRENT));
        clock.advance(Duration.ofSeconds(30));
        assertTrue(limiter.tryAcquire(CURRENT));
        assertFalse(limiter.tryAcquire(CURRENT));

        clock.advance(Duration.ofSeconds(31));
        assertTrue(limiter.tryAcquire(CURRENT), "le premier slot doit s'être libéré après 61s");
    }

    @Test
    void secondsUntilNextSlot_reflectsRemainingWaitOnTheOldestEntry() {
        MutableClock clock = new MutableClock();
        FootballDataRateLimiter limiter = limiter(1, 1, clock);

        assertTrue(limiter.tryAcquire(CURRENT));
        clock.advance(Duration.ofSeconds(15));
        assertFalse(limiter.tryAcquire(CURRENT));

        assertEquals(45, limiter.secondsUntilNextSlot(CURRENT));
    }

    @Test
    void secondsUntilNextSlot_isZeroWhenNoRequestIsTracked() {
        FootballDataRateLimiter limiter = limiter(5, 3, new MutableClock());

        assertEquals(0, limiter.secondsUntilNextSlot(CURRENT));
        assertEquals(0, limiter.secondsUntilNextSlot(HISTORICAL));
    }

    // ==================== Part des saisons terminées (#79) ====================

    @Test
    void finishedSeasonsNeverExceedTheirShare() {
        MutableClock clock = new MutableClock();
        FootballDataRateLimiter limiter = limiter(10, 3, clock);

        assertTrue(limiter.tryAcquire(HISTORICAL));
        assertTrue(limiter.tryAcquire(HISTORICAL));
        assertTrue(limiter.tryAcquire(HISTORICAL));
        assertFalse(limiter.tryAcquire(HISTORICAL), "4e appel de saison terminée dans la minute");

        // La part libère ses places avec la fenêtre glissante.
        clock.advance(Duration.ofSeconds(61));
        assertTrue(limiter.tryAcquire(HISTORICAL));
    }

    @Test
    void theCurrentSeasonCanUseAllTheRemainingQuota() {
        FootballDataRateLimiter limiter = limiter(10, 3, new MutableClock());
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.tryAcquire(HISTORICAL));
        }

        for (int i = 0; i < 7; i++) {
            assertTrue(limiter.tryAcquire(CURRENT), "appel " + (i + 1) + " de la saison en cours");
        }
        assertFalse(limiter.tryAcquire(CURRENT));
    }

    @Test
    void theTotalNeverExceedsTheGlobalQuotaWhateverTheCategories() {
        MutableClock clock = new MutableClock();
        FootballDataRateLimiter limiter = limiter(10, 3, clock);
        int accepted = 0;
        // Appels alternés pendant une minute : jamais plus de 10 acceptés dans la fenêtre.
        for (int i = 0; i < 40; i++) {
            if (limiter.tryAcquire(i % 2 == 0 ? CURRENT : HISTORICAL)) {
                accepted++;
            }
            clock.advance(Duration.ofSeconds(1));
        }
        assertEquals(10, accepted);
    }

    @Test
    void whenTheCurrentSeasonFillsTheQuotaFinishedSeasonsAreRefusedToo() {
        FootballDataRateLimiter limiter = limiter(10, 3, new MutableClock());
        for (int i = 0; i < 10; i++) {
            assertTrue(limiter.tryAcquire(CURRENT));
        }

        assertFalse(limiter.tryAcquire(HISTORICAL));
    }

    @Test
    void aShareLargerThanTheQuotaIsCappedToTheQuota() {
        FootballDataRateLimiter limiter = limiter(2, 5, new MutableClock());

        assertTrue(limiter.tryAcquire(HISTORICAL));
        assertTrue(limiter.tryAcquire(HISTORICAL));
        assertFalse(limiter.tryAcquire(HISTORICAL));
        assertFalse(limiter.tryAcquire(CURRENT));
    }

    // ==================== Retry-After par catégorie ====================

    @Test
    void retryAfterForFinishedSeasonsWaitsForTheOldestFinishedSeasonCall() {
        MutableClock clock = new MutableClock();
        FootballDataRateLimiter limiter = limiter(10, 2, clock);
        assertTrue(limiter.tryAcquire(CURRENT));          // t = 0
        clock.advance(Duration.ofSeconds(10));
        assertTrue(limiter.tryAcquire(HISTORICAL));       // t = 10
        clock.advance(Duration.ofSeconds(10));
        assertTrue(limiter.tryAcquire(HISTORICAL));       // t = 20
        clock.advance(Duration.ofSeconds(5));             // t = 25

        assertFalse(limiter.tryAcquire(HISTORICAL));
        // Le plus ancien appel de saison terminée (t = 10) sort de la fenêtre à t = 70.
        assertEquals(45, limiter.secondsUntilNextSlot(HISTORICAL));
        // La saison en cours a encore de la place.
        assertEquals(0, limiter.secondsUntilNextSlot(CURRENT));
    }

    @Test
    void retryAfterForTheCurrentSeasonWaitsForTheOldestCallOfAnyCategory() {
        MutableClock clock = new MutableClock();
        FootballDataRateLimiter limiter = limiter(3, 1, clock);
        assertTrue(limiter.tryAcquire(HISTORICAL));       // t = 0
        clock.advance(Duration.ofSeconds(20));
        assertTrue(limiter.tryAcquire(CURRENT));          // t = 20
        assertTrue(limiter.tryAcquire(CURRENT));
        clock.advance(Duration.ofSeconds(10));            // t = 30

        assertFalse(limiter.tryAcquire(CURRENT));
        assertEquals(30, limiter.secondsUntilNextSlot(CURRENT));
        assertEquals(30, limiter.secondsUntilNextSlot(HISTORICAL));
    }

    @Test
    void retryAfterForFinishedSeasonsWithANullShareIsTheWholeWindow() {
        FootballDataRateLimiter limiter = limiter(10, 0, new MutableClock());

        assertFalse(limiter.tryAcquire(HISTORICAL));
        assertEquals(60, limiter.secondsUntilNextSlot(HISTORICAL));
    }
}
