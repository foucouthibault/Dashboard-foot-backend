package fr.foucouthibault.dashboardfoot.service;

import fr.foucouthibault.dashboardfoot.config.CacheConfig;
import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamErrorException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUnavailableException;
import fr.foucouthibault.dashboardfoot.model.CompetitionResponse;
import fr.foucouthibault.dashboardfoot.model.ScorersResponse;
import fr.foucouthibault.dashboardfoot.model.StandingsResponse;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.http.*;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Year;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class FootballDataProxyServiceTest {

    @Mock
    private RestTemplate restTemplate;

    private CacheManager cacheManager;
    private FootballDataRateLimiter rateLimiter;
    private FootballDataProxyService service;
    private HttpHeaders headers;

    private final String currentSeason = String.valueOf(Year.now().getValue());
    private final String historicalSeason = String.valueOf(Year.now().getValue() - 5);

    @BeforeEach
    void setUp() throws Exception {
        headers = new HttpHeaders();
        cacheManager = buildCacheManager();
        // Vraie instance (pas de mock) pour ne pas retester la fenêtre glissante ici,
        // couverte par FootballDataRateLimiterTest ; large marge pour ne jamais gêner ces tests.
        rateLimiter = new FootballDataRateLimiter(1000);

        service = new FootballDataProxyService(restTemplate, cacheManager, rateLimiter);
        setField("apiBaseUrl", "https://api.football-data.org/v4");
        setField("apiToken", "test-token");
    }

    private void setField(String name, String value) throws Exception {
        setFieldOn(service, name, value);
    }

    private static void setFieldOn(FootballDataProxyService target, String name, String value) {
        try {
            Field field = FootballDataProxyService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CacheManager buildCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        Caffeine<Object, Object> generousTtl = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10));
        manager.registerCustomCache(CacheConfig.STATIC_CACHE, generousTtl.build());
        manager.registerCustomCache(CacheConfig.CURRENT_SEASON_CACHE, generousTtl.build());
        manager.registerCustomCache(CacheConfig.HISTORICAL_SEASON_CACHE, generousTtl.build());
        return manager;
    }

    private void mockUpstream(String url, ResponseEntity<String> response) {
        when(restTemplate.exchange(eq(URI.create(url)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(response);
    }

    // ==================== Cache ====================

    @Test
    void secondCallForSameCompetitionUsesCacheInsteadOfCallingUpstreamAgain() {
        String url = "https://api.football-data.org/v4/competitions/FL1";
        mockUpstream(url, new ResponseEntity<>("{\"id\":2015}", HttpStatus.OK));

        service.getCompetition("FL1", headers);
        service.getCompetition("FL1", headers);

        verify(restTemplate, times(1)).exchange(eq(URI.create(url)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void theCacheStoresTheTypedResponse() {
        String url = "https://api.football-data.org/v4/competitions/FL1";
        mockUpstream(url, new ResponseEntity<>("{\"id\":2015,\"name\":\"Ligue 1\"}", HttpStatus.OK));

        CompetitionResponse response = service.getCompetition("FL1", headers);

        Object cached = cacheManager.getCache(CacheConfig.STATIC_CACHE).get("competition_FL1").get();
        assertSame(response, cached);
        assertEquals(new CompetitionResponse(2015L, "Ligue 1", null, null), cached);
    }

    @Test
    void standingsForDifferentSeasonsAreCachedUnderDifferentKeys() {
        String url2024 = "https://api.football-data.org/v4/competitions/FL1/standings?season=2024";
        String url2025 = "https://api.football-data.org/v4/competitions/FL1/standings?season=2025";
        mockUpstream(url2024, new ResponseEntity<>("{\"standings\":[{\"stage\":\"S2024\"}]}", HttpStatus.OK));
        mockUpstream(url2025, new ResponseEntity<>("{\"standings\":[{\"stage\":\"S2025\"}]}", HttpStatus.OK));

        StandingsResponse r1 = service.getStandings("FL1", "2024", headers);
        StandingsResponse r2 = service.getStandings("FL1", "2025", headers);

        assertEquals("S2024", r1.standings().getFirst().stage());
        assertEquals("S2025", r2.standings().getFirst().stage());
        verify(restTemplate, times(2)).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void errorResponsesAreNotCached_soARetryHitsUpstreamAgain() {
        String url = "https://api.football-data.org/v4/competitions/FL1";
        when(restTemplate.exchange(eq(URI.create(url)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new RuntimeException("boom"))
            .thenReturn(new ResponseEntity<>("{\"id\":2015}", HttpStatus.OK));

        assertThrows(UpstreamUnavailableException.class, () -> service.getCompetition("FL1", headers));
        CompetitionResponse retried = service.getCompetition("FL1", headers);

        assertEquals(2015L, retried.id());
        verify(restTemplate, times(2)).exchange(eq(URI.create(url)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void historicalSeasonStandingsAreStoredInTheHistoricalCache() {
        String url = "https://api.football-data.org/v4/competitions/FL1/standings?season=" + historicalSeason;
        mockUpstream(url, new ResponseEntity<>("{\"standings\":[]}", HttpStatus.OK));

        service.getStandings("FL1", historicalSeason, headers);

        assertNotNull(cacheManager.getCache(CacheConfig.HISTORICAL_SEASON_CACHE).get("standings_FL1_" + historicalSeason));
        assertNull(cacheManager.getCache(CacheConfig.CURRENT_SEASON_CACHE).get("standings_FL1_" + historicalSeason));
    }

    @Test
    void currentSeasonStandingsAreStoredInTheCurrentSeasonCache() {
        String url = "https://api.football-data.org/v4/competitions/FL1/standings?season=" + currentSeason;
        mockUpstream(url, new ResponseEntity<>("{\"standings\":[]}", HttpStatus.OK));

        service.getStandings("FL1", currentSeason, headers);

        assertNotNull(cacheManager.getCache(CacheConfig.CURRENT_SEASON_CACHE).get("standings_FL1_" + currentSeason));
        assertNull(cacheManager.getCache(CacheConfig.HISTORICAL_SEASON_CACHE).get("standings_FL1_" + currentSeason));
    }

    // ==================== Erreurs ====================

    @Test
    void upstreamHttpErrorStatusIsPropagatedInsteadOfGeneric502() {
        String url = "https://api.football-data.org/v4/competitions/FL1/scorers?limit=50&season=2026";
        String upstreamError = "{\"message\":\"The season 2026 is restricted for your subscription.\"}";
        when(restTemplate.exchange(eq(URI.create(url)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", HttpHeaders.EMPTY,
                upstreamError.getBytes(StandardCharsets.UTF_8), null));

        UpstreamErrorException e = assertThrows(UpstreamErrorException.class,
            () -> service.getScorers("FL1", null, "2026", headers));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }

    @Test
    void genericFailureReturns502WithoutLeakingTheExceptionMessage() {
        String url = "https://api.football-data.org/v4/competitions/FL1";
        when(restTemplate.exchange(eq(URI.create(url)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new RuntimeException("secret internal detail"));

        UpstreamUnavailableException e = assertThrows(UpstreamUnavailableException.class,
            () -> service.getCompetition("FL1", headers));

        assertFalse(e.getMessage().contains("secret internal detail"));
    }

    // ==================== Rate limiting ====================

    @Test
    void whenRateLimitIsExhausted_returns429WithoutCallingUpstream() {
        FootballDataRateLimiter exhausted = mock(FootballDataRateLimiter.class);
        when(exhausted.tryAcquire()).thenReturn(false);
        when(exhausted.secondsUntilNextSlot()).thenReturn(37L);
        FootballDataProxyService limitedService = new FootballDataProxyService(restTemplate, cacheManager, exhausted);

        RateLimitExceededException e = assertThrows(RateLimitExceededException.class,
            () -> limitedService.getCompetition("FL1", headers));

        assertEquals(37L, e.getRetryAfterSeconds());
        verifyNoInteractions(restTemplate);
    }

    // ==================== Logs ====================

    @Test
    void idContainingALineBreakCannotForgeAFakeLogLine(CapturedOutput output) {
        FootballDataRateLimiter exhausted = mock(FootballDataRateLimiter.class);
        when(exhausted.tryAcquire()).thenReturn(false);
        FootballDataProxyService limitedService = new FootballDataProxyService(restTemplate, cacheManager, exhausted);
        setFieldOn(limitedService, "apiBaseUrl", "https://api.football-data.org/v4");

        assertThrows(RateLimitExceededException.class,
            () -> limitedService.getCompetition("FL1\nERROR FAUSSE LIGNE", headers));

        assertTrue(output.getOut().contains("FL1_ERROR FAUSSE LIGNE"));
        assertTrue(output.getOut().lines().noneMatch(line -> line.startsWith("ERROR FAUSSE LIGNE")));
    }

    @Test
    void upstreamErrorBodyIsNeutralizedBeforeBeingLogged(CapturedOutput output) {
        String url = "https://api.football-data.org/v4/competitions/FL1";
        when(restTemplate.exchange(eq(URI.create(url)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(),
                "refus\r\nERROR FAUSSE LIGNE".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        assertThrows(UpstreamErrorException.class, () -> service.getCompetition("FL1", headers));

        assertTrue(output.getOut().contains("refus__ERROR FAUSSE LIGNE"));
        assertTrue(output.getOut().lines().noneMatch(line -> line.startsWith("ERROR FAUSSE LIGNE")));
    }

    @Test
    void forLogReplacesEveryControlCharacter() {
        assertEquals("a_b_c_d_e", FootballDataProxyService.forLog("a\rb\nc\td\u001Be"));
        assertEquals("FL1", FootballDataProxyService.forLog("FL1"));
        assertNull(FootballDataProxyService.forLog(null));
    }

    // ==================== Buteurs : limit et troncature ====================

    @Test
    void scorersRequestAlwaysAsksUpstreamForTheMaxLimitRegardlessOfClientLimit() {
        String url = "https://api.football-data.org/v4/competitions/FL1/scorers?limit=50";
        mockUpstream(url, new ResponseEntity<>(scorersJson(5), HttpStatus.OK));

        service.getScorers("FL1", 5, null, headers);

        verify(restTemplate).exchange(eq(URI.create(url)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void differentClientLimitsReuseTheSameCachedUpstreamCall() {
        String url = "https://api.football-data.org/v4/competitions/FL1/scorers?limit=50";
        mockUpstream(url, new ResponseEntity<>(scorersJson(20), HttpStatus.OK));

        service.getScorers("FL1", 5, null, headers);
        service.getScorers("FL1", 10, null, headers);
        service.getScorers("FL1", 20, null, headers);

        verify(restTemplate, times(1)).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void scorersResponseIsTrimmedToTheRequestedLimit() {
        String url = "https://api.football-data.org/v4/competitions/FL1/scorers?limit=50";
        mockUpstream(url, new ResponseEntity<>(scorersJson(20), HttpStatus.OK));

        ScorersResponse response = service.getScorers("FL1", 3, null, headers);

        assertEquals(3, response.count());
        assertEquals(List.of(20, 19, 18), response.scorers().stream().map(s -> s.goals()).toList());
    }

    @Test
    void scorersResponseIsNotTrimmedWhenFewerThanRequested() {
        String url = "https://api.football-data.org/v4/competitions/FL1/scorers?limit=50";
        mockUpstream(url, new ResponseEntity<>(scorersJson(4), HttpStatus.OK));

        ScorersResponse response = service.getScorers("FL1", 10, null, headers);

        assertEquals(4, response.count());
        assertEquals(4, response.scorers().size());
    }

    private static String scorersJson(int count) {
        StringBuilder sb = new StringBuilder("{\"count\":").append(count).append(",\"scorers\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"player\":{\"name\":\"Player ").append(i).append("\"},\"goals\":").append(count - i).append("}");
        }
        return sb.append("]}").toString();
    }
}
