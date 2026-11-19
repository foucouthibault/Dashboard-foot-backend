package fr.foucouthibault.dashboardfoot.service;

import fr.foucouthibault.dashboardfoot.config.CacheConfig;
import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.time.Year;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Partage du quota (#79) vu du service : les saisons terminées passent par la part qui leur est
 * réservée, la saison en cours, les matchs et les compétitions par le reste du quota. Limiteur
 * réel à temps simulé, {@link RestTemplate} mocké.
 */
class FootballDataProxyServiceQuotaShareTest {

    private static final int THIS_YEAR = Year.now().getValue();

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final SimulatedTime time = new SimulatedTime();
    private FootballDataProxyService service;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        FootballDataRateLimiter limiter = new FootballDataRateLimiter(10, 3, Duration.ofMinutes(1), time.clock());
        CaffeineCacheManager cacheManager = new CaffeineCacheManager(
            CacheConfig.COMPETITIONS_CACHE, CacheConfig.STATIC_CACHE, CacheConfig.CURRENT_SEASON_CACHE, CacheConfig.HISTORICAL_SEASON_CACHE);
        service = new FootballDataProxyService(restTemplate, cacheManager, limiter);
        Field field = FootballDataProxyService.class.getDeclaredField("apiBaseUrl");
        field.setAccessible(true);
        field.set(service, "https://api.football-data.org/v4");
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("{\"standings\":[]}", HttpStatus.OK));
    }

    private void finishedSeason(int yearsAgo) {
        service.getStandings("FL1", String.valueOf(THIS_YEAR - yearsAgo), new HttpHeaders());
    }

    @Test
    void finishedSeasonsUseTheirShareAndTheCurrentSeasonIsStillServed() {
        finishedSeason(1);
        finishedSeason(2);
        finishedSeason(3);

        time.advance(Duration.ofSeconds(20));
        RateLimitExceededException e = assertThrows(RateLimitExceededException.class, () -> finishedSeason(4));
        // Le premier appel de saison terminée (t = 0) sort de la fenêtre à t = 60.
        assertEquals(40, e.getRetryAfterSeconds());

        assertDoesNotThrow(() -> service.getStandings("FL1", null, new HttpHeaders()));
        assertDoesNotThrow(() -> service.getStandings("FL1", String.valueOf(THIS_YEAR), new HttpHeaders()));
        assertDoesNotThrow(() -> service.getMatches("FL1", new HttpHeaders()));
        assertDoesNotThrow(() -> service.getCompetition("FL1", new HttpHeaders()));
    }

    @Test
    void onlyTheHistoricalCacheCountsAsFinishedSeasons() {
        assertEquals(QuotaCategory.HISTORICAL, FootballDataProxyService.quotaCategory(CacheConfig.HISTORICAL_SEASON_CACHE));
        assertEquals(QuotaCategory.CURRENT, FootballDataProxyService.quotaCategory(CacheConfig.CURRENT_SEASON_CACHE));
        assertEquals(QuotaCategory.CURRENT, FootballDataProxyService.quotaCategory(CacheConfig.STATIC_CACHE));
        assertEquals(QuotaCategory.CURRENT, FootballDataProxyService.quotaCategory(CacheConfig.COMPETITIONS_CACHE));
    }
}
