package fr.foucouthibault.dashboardfoot.config;

import fr.foucouthibault.dashboardfoot.model.MatchesResponse;
import fr.foucouthibault.dashboardfoot.model.MatchesResponse.Match;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Durée de vie déduite des matchs d'une compétition ({@link MatchAwareTtl}), à heure fixe. */
class MatchAwareTtlTest {

    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");
    private static final Duration LIVE = Duration.ofSeconds(60);
    private static final Duration MIN = Duration.ofSeconds(60);
    private static final Duration MAX = Duration.ofHours(1);

    private final MatchAwareTtl ttl = new MatchAwareTtl(LIVE, MIN, MAX, Clock.fixed(NOW, ZoneOffset.UTC));

    private static Match match(String status, Instant kickoff) {
        return new Match(1L, kickoff == null ? null : kickoff.toString(), status, 1, null, null, null);
    }

    private static MatchesResponse matches(Match... matches) {
        return new MatchesResponse(Arrays.asList(matches));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IN_PLAY", "PAUSED"})
    void aMatchInProgressGivesTheShortDuration(String status) {
        MatchesResponse response = matches(
            match("FINISHED", NOW.minus(Duration.ofDays(3))),
            match(status, NOW.minus(Duration.ofMinutes(30))),
            match("TIMED", NOW.plus(Duration.ofDays(4))));

        assertEquals(LIVE, ttl.ttlFor(response));
    }

    @Test
    void withoutMatchInProgressTheEntryLivesUntilTheNextKickoff() {
        MatchesResponse response = matches(
            match("FINISHED", NOW.minus(Duration.ofDays(3))),
            match("TIMED", NOW.plus(Duration.ofMinutes(40))),
            match("SCHEDULED", NOW.plus(Duration.ofMinutes(25))));

        assertEquals(Duration.ofMinutes(25), ttl.ttlFor(response));
    }

    @Test
    void aDistantKickoffIsCappedByTheMaximum() {
        assertEquals(MAX, ttl.ttlFor(matches(match("TIMED", NOW.plus(Duration.ofDays(3))))));
    }

    @Test
    void anImminentKickoffIsRaisedToTheMinimum() {
        assertEquals(MIN, ttl.ttlFor(matches(match("TIMED", NOW.plusSeconds(10)))));
    }

    @Test
    void withoutUpcomingMatchTheMaximumApplies() {
        assertEquals(MAX, ttl.ttlFor(matches(match("FINISHED", NOW.minus(Duration.ofDays(1))))));
        assertEquals(MAX, ttl.ttlFor(matches()));
    }

    /** Coup d'envoi passé, statut amont pas encore à jour : le match est sans doute en cours. */
    @Test
    void aRecentKickoffStillAnnouncedAsUpcomingCountsAsInProgress() {
        assertEquals(LIVE, ttl.ttlFor(matches(match("TIMED", NOW.minus(Duration.ofMinutes(5))))));
    }

    /** Statut figé depuis longtemps (match reporté) : ne garde pas le cache au plus court. */
    @Test
    void anOldKickoffStillAnnouncedAsUpcomingIsIgnored() {
        MatchesResponse response = matches(
            match("TIMED", NOW.minus(Duration.ofDays(10))),
            match("TIMED", NOW.plus(Duration.ofMinutes(20))));

        assertEquals(Duration.ofMinutes(20), ttl.ttlFor(response));
    }

    @Test
    void unreadableOrMissingDatesAndStatusesAreIgnored() {
        MatchesResponse response = matches(
            new Match(1L, "pas une date", "TIMED", 1, null, null, null),
            match("TIMED", null),
            match(null, NOW.plusSeconds(120)));

        assertEquals(MAX, ttl.ttlFor(response));
    }

    @Test
    void theCompetitionIsReadFromASeasonKey() {
        assertEquals("FL1", CacheConfig.competitionOf(CacheConfig.seasonKey("standings", "FL1", "current")));
        assertEquals("ma_ligue", CacheConfig.competitionOf(CacheConfig.seasonKey("scorers", "ma_ligue", "2026")));
        assertNull(CacheConfig.competitionOf("sans-separateur"));
        assertNull(CacheConfig.competitionOf(42));
    }
}
