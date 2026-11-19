package fr.foucouthibault.dashboardfoot.service;

import com.github.benmanes.caffeine.cache.Ticker;
import fr.foucouthibault.dashboardfoot.config.CacheConfig;
import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamErrorException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUnavailableException;
import fr.foucouthibault.dashboardfoot.model.CompetitionResponse;
import fr.foucouthibault.dashboardfoot.model.MatchesResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Copie de secours (#76) : une réponse expirée du cache principal est servie à la place d'une
 * erreur passagère (quota atteint, {@code 429}/{@code 5xx} amont, panne réseau), sans consommer
 * de quota, avec son âge. Temps simulé par un {@link Ticker}, durées par défaut de
 * {@link CacheConfig} ; {@link RestTemplate} est mocké.
 */
class FootballDataProxyServiceFallbackTest {

    private static final String BASE = "https://api.football-data.org/v4";
    private static final URI COMPETITION_URI = URI.create(BASE + "/competitions/FL1");
    private static final URI MATCHES_URI = URI.create(BASE + "/competitions/FL1/matches");
    /** Plus que la durée du cache principal des compétitions (24 h), moins que celle de la copie. */
    private static final Duration AFTER_MAIN_EXPIRY = Duration.ofHours(25);

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final SimulatedTime ticker = new SimulatedTime();
    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private FootballDataRateLimiter rateLimiter;
    private FootballDataProxyService service;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        AtomicReference<CacheConfig> defaults = new AtomicReference<>();
        new ApplicationContextRunner()
            .withUserConfiguration(CacheConfig.class)
            .run(context -> defaults.set(context.getBean(CacheConfig.class)));
        CacheManager cacheManager = defaults.get().cacheManager(ticker, ticker.clock());
        rateLimiter = mock(FootballDataRateLimiter.class);
        when(rateLimiter.tryAcquire()).thenReturn(true);
        service = new FootballDataProxyService(restTemplate, cacheManager, rateLimiter);
        setField("apiBaseUrl", BASE);
        setField("apiToken", "test-token");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void setField(String name, String value) throws ReflectiveOperationException {
        Field field = FootballDataProxyService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private void upstreamAnswersThenFails(URI uri, String body, RuntimeException failure) {
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK))
            .thenThrow(failure);
    }

    private void verifyOutgoingCalls(URI uri, int times) {
        verify(restTemplate, times(times)).exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    /** Charge la compétition une fois (copie créée), puis laisse expirer le cache principal. */
    private CompetitionResponse loadThenExpire(RuntimeException nextFailure) {
        upstreamAnswersThenFails(COMPETITION_URI, "{\"id\":2015,\"name\":\"Ligue 1\"}", nextFailure);
        CompetitionResponse fresh = service.getCompetition("FL1", new HttpHeaders());
        ticker.advance(AFTER_MAIN_EXPIRY);
        return fresh;
    }

    private Optional<Duration> markedAge() {
        return StaleResponse.ageOf(request);
    }

    // ==================== Quota interne ====================

    @Test
    void anExpiredEntryIsServedFromTheCopyWhenTheLimiterRefusesWithoutAnyOutgoingCall() {
        CompetitionResponse fresh = loadThenExpire(new IllegalStateException("ne doit pas être appelé"));
        when(rateLimiter.tryAcquire()).thenReturn(false);

        CompetitionResponse served = service.getCompetition("FL1", new HttpHeaders());

        assertEquals(fresh, served);
        verifyOutgoingCalls(COMPETITION_URI, 1);
        assertEquals(Optional.of(AFTER_MAIN_EXPIRY), markedAge());
    }

    @Test
    void aFreshResponseIsNotMarkedAsStale() {
        upstreamAnswersThenFails(COMPETITION_URI, "{\"id\":2015}", new IllegalStateException());

        service.getCompetition("FL1", new HttpHeaders());
        service.getCompetition("FL1", new HttpHeaders());

        assertEquals(Optional.empty(), markedAge());
    }

    /** La copie ne remplace pas l'entrée principale : un nouvel appel est tenté au créneau suivant. */
    @Test
    void servingTheCopyDoesNotRefillTheMainCache() {
        when(restTemplate.exchange(eq(COMPETITION_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("{\"id\":2015}", HttpStatus.OK));
        service.getCompetition("FL1", new HttpHeaders());
        ticker.advance(AFTER_MAIN_EXPIRY);
        when(rateLimiter.tryAcquire()).thenReturn(false);
        service.getCompetition("FL1", new HttpHeaders());

        when(rateLimiter.tryAcquire()).thenReturn(true);
        service.getCompetition("FL1", new HttpHeaders());

        verifyOutgoingCalls(COMPETITION_URI, 2);
    }

    // ==================== Pannes de football-data.org ====================

    @ParameterizedTest
    @ValueSource(ints = {429, 500, 502, 503, 504})
    void anExpiredEntryIsServedFromTheCopyOnUpstream429Or5xx(int status) {
        RuntimeException failure = status == 429
            ? new HttpClientErrorException(HttpStatusCode.valueOf(status))
            : new HttpServerErrorException(HttpStatusCode.valueOf(status));
        CompetitionResponse fresh = loadThenExpire(failure);

        assertEquals(fresh, service.getCompetition("FL1", new HttpHeaders()));
        verifyOutgoingCalls(COMPETITION_URI, 2);
        assertTrue(markedAge().isPresent());
    }

    @Test
    void anExpiredEntryIsServedFromTheCopyOnNetworkFailure() {
        CompetitionResponse fresh = loadThenExpire(new ResourceAccessException("Connection refused"));

        assertEquals(fresh, service.getCompetition("FL1", new HttpHeaders()));
    }

    @Test
    void anExpiredEntryIsServedFromTheCopyWhenTheUpstreamBodyIsUnreadable() {
        when(restTemplate.exchange(eq(COMPETITION_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>("{\"id\":2015}", HttpStatus.OK))
            .thenReturn(new ResponseEntity<>("pas du JSON", HttpStatus.OK));
        CompetitionResponse fresh = service.getCompetition("FL1", new HttpHeaders());
        ticker.advance(AFTER_MAIN_EXPIRY);

        assertEquals(fresh, service.getCompetition("FL1", new HttpHeaders()));
    }

    // ==================== Vraies réponses : relayées ====================

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404})
    void anUpstreamClientErrorIsStillRelayedEvenWithACopy(int status) {
        loadThenExpire(new HttpClientErrorException(HttpStatusCode.valueOf(status)));

        UpstreamErrorException e = assertThrows(UpstreamErrorException.class,
            () -> service.getCompetition("FL1", new HttpHeaders()));

        assertEquals(status, e.getStatus().value());
        assertEquals(Optional.empty(), markedAge());
    }

    // ==================== Sans copie ====================

    @Test
    void withoutACopyTheLimiterRefusalStillReaches429() {
        when(rateLimiter.tryAcquire()).thenReturn(false);
        when(rateLimiter.secondsUntilNextSlot()).thenReturn(12L);

        RateLimitExceededException e = assertThrows(RateLimitExceededException.class,
            () -> service.getCompetition("FL1", new HttpHeaders()));

        assertEquals(12L, e.getRetryAfterSeconds());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void withoutACopyANetworkFailureStillReaches502() {
        when(restTemplate.exchange(eq(COMPETITION_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(new ResourceAccessException("Connection refused"));

        assertThrows(UpstreamUnavailableException.class, () -> service.getCompetition("FL1", new HttpHeaders()));
    }

    /** Copie d'une autre clé : jamais servie pour celle-ci. */
    @Test
    void theCopyOfAnotherCompetitionIsNeverServed() {
        loadThenExpire(new IllegalStateException());
        when(rateLimiter.tryAcquire()).thenReturn(false);

        assertThrows(RateLimitExceededException.class, () -> service.getCompetition("PL", new HttpHeaders()));
    }

    // ==================== Durée de vie de la copie ====================

    @Test
    void aCopyOlderThanItsLifetimeIsNoLongerServed() {
        upstreamAnswersThenFails(MATCHES_URI, "{\"matches\":[]}", new ResourceAccessException("Connection refused"));
        service.getMatches("FL1", new HttpHeaders());

        // Matchs : copie gardée 6 h par défaut.
        ticker.advance(Duration.ofHours(6).minusSeconds(1));
        MatchesResponse served = service.getMatches("FL1", new HttpHeaders());
        assertNotNull(served);

        ticker.advance(Duration.ofSeconds(2));
        assertThrows(UpstreamUnavailableException.class, () -> service.getMatches("FL1", new HttpHeaders()));
    }

    @Test
    void applicationPropertiesUseTheSameDefaultCopyLifetimes() throws Exception {
        Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertEquals("168", properties.getProperty("football-data.cache.fallback.competition-ttl-hours"));
        assertEquals("6", properties.getProperty("football-data.cache.fallback.static-ttl-hours"));
        assertEquals("24", properties.getProperty("football-data.cache.fallback.current-season-ttl-hours"));
        assertEquals("1440", properties.getProperty("football-data.cache.fallback.historical-season-ttl-hours"));
    }

    // ==================== Hors requête HTTP ====================

    @Test
    void outsideAnHttpRequestTheCopyIsStillServed() {
        RequestContextHolder.resetRequestAttributes();
        CompetitionResponse fresh = loadThenExpire(new IllegalStateException());
        when(rateLimiter.tryAcquire()).thenReturn(false);

        assertEquals(fresh, service.getCompetition("FL1", new HttpHeaders()));
    }
}
