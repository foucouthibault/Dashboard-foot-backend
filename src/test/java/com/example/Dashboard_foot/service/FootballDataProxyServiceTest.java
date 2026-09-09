package com.example.Dashboard_foot.service;

import com.example.Dashboard_foot.config.CacheConfig;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
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
        Field field = FootballDataProxyService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
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
    void standingsForDifferentSeasonsAreCachedUnderDifferentKeys() {
        String url2024 = "https://api.football-data.org/v4/competitions/FL1/standings?season=2024";
        String url2025 = "https://api.football-data.org/v4/competitions/FL1/standings?season=2025";
        mockUpstream(url2024, new ResponseEntity<>("{\"season\":\"2024\"}", HttpStatus.OK));
        mockUpstream(url2025, new ResponseEntity<>("{\"season\":\"2025\"}", HttpStatus.OK));

        ResponseEntity<String> r1 = service.getStandings("FL1", "2024", headers);
        ResponseEntity<String> r2 = service.getStandings("FL1", "2025", headers);

        assertEquals("{\"season\":\"2024\"}", r1.getBody());
        assertEquals("{\"season\":\"2025\"}", r2.getBody());
        verify(restTemplate, times(2)).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void errorResponsesAreNotCached_soARetryHitsUpstreamAgain() {
        String url = "https://api.football-data.org/v4/competitions/FL1";
        when(restTemplate.exchange(eq(URI.create(url)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new RuntimeException("boom"))
            .thenReturn(new ResponseEntity<>("{\"id\":2015}", HttpStatus.OK));

        ResponseEntity<String> failed = service.getCompetition("FL1", headers);
        ResponseEntity<String> retried = service.getCompetition("FL1", headers);

        assertEquals(HttpStatus.BAD_GATEWAY, failed.getStatusCode());
        assertEquals(HttpStatus.OK, retried.getStatusCode());
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

        ResponseEntity<String> response = service.getScorers("FL1", null, "2026", headers);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals(upstreamError, response.getBody());
    }

    @Test
    void genericFailureReturns502WithoutLeakingTheExceptionMessage() {
        String url = "https://api.football-data.org/v4/competitions/FL1";
        when(restTemplate.exchange(eq(URI.create(url)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new RuntimeException("secret internal detail"));

        ResponseEntity<String> response = service.getCompetition("FL1", headers);

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertFalse(response.getBody().contains("secret internal detail"));
    }

    // ==================== Rate limiting ====================

    @Test
    void whenRateLimitIsExhausted_returns429WithoutCallingUpstream() {
        FootballDataRateLimiter exhausted = mock(FootballDataRateLimiter.class);
        when(exhausted.tryAcquire()).thenReturn(false);
        when(exhausted.secondsUntilNextSlot()).thenReturn(37L);
        FootballDataProxyService limitedService = new FootballDataProxyService(restTemplate, cacheManager, exhausted);

        ResponseEntity<String> response = limitedService.getCompetition("FL1", headers);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertEquals("37", response.getHeaders().getFirst("Retry-After"));
        verifyNoInteractions(restTemplate);
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

        ResponseEntity<String> response = service.getScorers("FL1", 3, null, headers);

        assertTrue(response.getBody().contains("\"count\":3"));
        assertEquals(3, countOccurrences(response.getBody(), "\"goals\""));
    }

    @Test
    void scorersResponseIsNotTrimmedWhenFewerThanRequested() {
        String url = "https://api.football-data.org/v4/competitions/FL1/scorers?limit=50";
        String body = scorersJson(4);
        mockUpstream(url, new ResponseEntity<>(body, HttpStatus.OK));

        ResponseEntity<String> response = service.getScorers("FL1", 10, null, headers);

        assertEquals(body, response.getBody());
    }

    private static String scorersJson(int count) {
        StringBuilder sb = new StringBuilder("{\"count\":").append(count).append(",\"scorers\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"player\":{\"name\":\"Player ").append(i).append("\"},\"goals\":").append(count - i).append("}");
        }
        return sb.append("]}").toString();
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
