package fr.foucouthibault.dashboardfoot.service;

import fr.foucouthibault.dashboardfoot.config.CacheConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Durée de vie des matchs, du classement et des buteurs de la saison en cours selon les matchs
 * de la compétition (#77) : courte pendant un match, jusqu'au prochain coup d'envoi sinon.
 * Temps simulé ({@link SimulatedTime}), durées par défaut de {@link CacheConfig},
 * {@link RestTemplate} mocké.
 */
class FootballDataProxyServiceMatchAwareCacheTest {

    private static final String BASE = "https://api.football-data.org/v4";
    private static final URI MATCHES_URI = URI.create(BASE + "/competitions/FL1/matches");
    private static final URI STANDINGS_URI = URI.create(BASE + "/competitions/FL1/standings");
    private static final URI SCORERS_URI = URI.create(BASE + "/competitions/FL1/scorers?limit=50");

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final SimulatedTime time = new SimulatedTime();
    private FootballDataProxyService service;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        AtomicReference<CacheConfig> defaults = new AtomicReference<>();
        new ApplicationContextRunner()
            .withUserConfiguration(CacheConfig.class)
            .run(context -> defaults.set(context.getBean(CacheConfig.class)));
        CacheManager cacheManager = defaults.get().cacheManager(time, time.clock());
        FootballDataRateLimiter rateLimiter = mock(FootballDataRateLimiter.class);
        when(rateLimiter.tryAcquire()).thenReturn(true);
        service = new FootballDataProxyService(restTemplate, cacheManager, rateLimiter);
        setField("apiBaseUrl", BASE);
        setField("apiToken", "test-token");

        upstreamAnswers(STANDINGS_URI, "{\"standings\":[]}");
        upstreamAnswers(SCORERS_URI, "{\"count\":0,\"scorers\":[]}");
    }

    private void setField(String name, String value) throws ReflectiveOperationException {
        Field field = FootballDataProxyService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private void upstreamAnswers(URI uri, String body) {
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    private void verifyOutgoingCalls(URI uri, int times) {
        verify(restTemplate, times(times)).exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    /** Matchs de FL1 : un match au statut donné, coup d'envoi décalé de {@code fromNow}. */
    private void matchesAre(String status, Duration fromNow) {
        Instant kickoff = time.now().plus(fromNow);
        upstreamAnswers(MATCHES_URI,
            "{\"matches\":[{\"id\":1,\"utcDate\":\"" + kickoff + "\",\"status\":\"" + status + "\",\"matchday\":12}]}");
    }

    private void loadAll() {
        service.getMatches("FL1", new HttpHeaders());
        service.getStandings("FL1", null, new HttpHeaders());
        service.getScorers("FL1", 10, null, new HttpHeaders());
    }

    // ==================== Match en cours ====================

    @Test
    void withAMatchInProgressEverythingExpiresAfterTheShortDuration() {
        matchesAre("IN_PLAY", Duration.ofMinutes(-30));
        loadAll();

        time.advance(Duration.ofSeconds(59));
        loadAll();
        verifyOutgoingCalls(MATCHES_URI, 1);
        verifyOutgoingCalls(STANDINGS_URI, 1);
        verifyOutgoingCalls(SCORERS_URI, 1);

        time.advance(Duration.ofSeconds(2));
        loadAll();
        verifyOutgoingCalls(MATCHES_URI, 2);
        verifyOutgoingCalls(STANDINGS_URI, 2);
        verifyOutgoingCalls(SCORERS_URI, 2);
    }

    // ==================== Sans match en cours ====================

    @Test
    void withoutMatchInProgressEntriesLiveUntilTheNextKickoff() {
        matchesAre("TIMED", Duration.ofMinutes(40));
        loadAll();

        time.advance(Duration.ofMinutes(40).minusSeconds(1));
        loadAll();
        verifyOutgoingCalls(MATCHES_URI, 1);
        verifyOutgoingCalls(STANDINGS_URI, 1);
        verifyOutgoingCalls(SCORERS_URI, 1);

        time.advance(Duration.ofSeconds(2));
        loadAll();
        verifyOutgoingCalls(MATCHES_URI, 2);
        verifyOutgoingCalls(STANDINGS_URI, 2);
    }

    @Test
    void aDistantKickoffIsCappedAtOneHour() {
        matchesAre("TIMED", Duration.ofDays(3));
        loadAll();

        time.advance(Duration.ofHours(1).minusSeconds(1));
        loadAll();
        verifyOutgoingCalls(MATCHES_URI, 1);
        verifyOutgoingCalls(STANDINGS_URI, 1);

        time.advance(Duration.ofSeconds(2));
        loadAll();
        verifyOutgoingCalls(MATCHES_URI, 2);
        verifyOutgoingCalls(STANDINGS_URI, 2);
    }

    // ==================== Matchs absents du cache ====================

    /** Sans matchs en cache, la durée par défaut (5 min) s'applique, sans appel aux matchs. */
    @Test
    void withoutCachedMatchesTheStandingsKeepTheDefaultDurationAndMatchesAreNeverFetched() {
        service.getStandings("FL1", null, new HttpHeaders());

        time.advance(Duration.ofMinutes(5).minusSeconds(1));
        service.getStandings("FL1", null, new HttpHeaders());
        verifyOutgoingCalls(STANDINGS_URI, 1);

        time.advance(Duration.ofSeconds(2));
        service.getStandings("FL1", null, new HttpHeaders());
        verifyOutgoingCalls(STANDINGS_URI, 2);
        verifyOutgoingCalls(MATCHES_URI, 0);
    }

    /** Les matchs d'une autre compétition ne comptent pas. */
    @Test
    void theMatchesOfAnotherCompetitionAreIgnored() {
        URI otherMatches = URI.create(BASE + "/competitions/PL/matches");
        upstreamAnswers(otherMatches, "{\"matches\":[{\"id\":2,\"utcDate\":\"" + time.now().minusSeconds(600)
            + "\",\"status\":\"IN_PLAY\"}]}");
        service.getMatches("PL", new HttpHeaders());
        service.getStandings("FL1", null, new HttpHeaders());

        time.advance(Duration.ofMinutes(2));
        service.getStandings("FL1", null, new HttpHeaders());

        verifyOutgoingCalls(STANDINGS_URI, 1);
    }
}
