package fr.foucouthibault.dashboardfoot.service;

import com.github.benmanes.caffeine.cache.Ticker;
import fr.foucouthibault.dashboardfoot.config.CacheConfig;
import fr.foucouthibault.dashboardfoot.model.CompetitionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.Duration;
import java.time.Year;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Durées de vie des caches de {@link CacheConfig} (temps simulé par un {@link Ticker}) et
 * chargement unique par clé, même avec des requêtes simultanées. Aucun appel réseau :
 * {@link RestTemplate} est mocké.
 */
@ExtendWith(MockitoExtension.class)
class FootballDataProxyServiceCacheTest {

    private static final String BASE = "https://api.football-data.org/v4";
    private static final URI COMPETITION_URI = URI.create(BASE + "/competitions/FL1");
    private static final String HISTORICAL_SEASON = String.valueOf(Year.now().getValue() - 5);
    private static final URI HISTORICAL_STANDINGS_URI =
        URI.create(BASE + "/competitions/FL1/standings?season=" + HISTORICAL_SEASON);

    @Mock
    private RestTemplate restTemplate;

    private final SimulatedTime ticker = new SimulatedTime();
    private FootballDataRateLimiter rateLimiter;
    private FootballDataProxyService service;

    @BeforeEach
    void setUp() {
        // Durées par défaut, lues comme en production (valeurs par défaut des @Value).
        AtomicReference<CacheConfig> defaults = new AtomicReference<>();
        new ApplicationContextRunner()
            .withUserConfiguration(CacheConfig.class)
            .run(context -> defaults.set(context.getBean(CacheConfig.class)));
        CacheManager cacheManager = defaults.get().cacheManager(ticker, ticker.clock());
        rateLimiter = spy(new FootballDataRateLimiter(1000, 1000));
        service = new FootballDataProxyService(restTemplate, cacheManager, rateLimiter);
        setField("apiBaseUrl", BASE);
        setField("apiToken", "test-token");
    }

    private void setField(String name, String value) {
        try {
            Field field = FootballDataProxyService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void upstreamAnswers(URI uri, String body) {
        when(restTemplate.exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(new ResponseEntity<>(body, HttpStatus.OK));
    }

    private void verifyOutgoingCalls(URI uri, int times) {
        verify(restTemplate, times(times)).exchange(eq(uri), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    // ==================== Durées de vie ====================

    @Test
    void aCompetitionIsServedWithoutOutgoingCallForTheWholeConfiguredDuration() {
        upstreamAnswers(COMPETITION_URI, "{\"id\":2015}");

        service.getCompetition("FL1", new HttpHeaders());
        ticker.advance(Duration.ofHours(24).minusSeconds(1));
        service.getCompetition("FL1", new HttpHeaders());
        verifyOutgoingCalls(COMPETITION_URI, 1);
        verify(rateLimiter, times(1)).tryAcquire(any());

        ticker.advance(Duration.ofSeconds(2));
        service.getCompetition("FL1", new HttpHeaders());
        verifyOutgoingCalls(COMPETITION_URI, 2);
    }

    @Test
    void aFinishedSeasonIsKeptThirtyDaysByDefault() {
        upstreamAnswers(HISTORICAL_STANDINGS_URI, "{\"standings\":[]}");

        service.getStandings("FL1", HISTORICAL_SEASON, new HttpHeaders());
        ticker.advance(Duration.ofDays(30).minusSeconds(1));
        service.getStandings("FL1", HISTORICAL_SEASON, new HttpHeaders());
        verifyOutgoingCalls(HISTORICAL_STANDINGS_URI, 1);

        ticker.advance(Duration.ofSeconds(2));
        service.getStandings("FL1", HISTORICAL_SEASON, new HttpHeaders());
        verifyOutgoingCalls(HISTORICAL_STANDINGS_URI, 2);
    }

    @Test
    void applicationPropertiesUseTheSameDefaultDurations() throws Exception {
        Properties properties = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertEquals("24", properties.getProperty("football-data.cache.competition-ttl-hours"));
        assertEquals("720", properties.getProperty("football-data.cache.historical-season-ttl-hours"));
        assertEquals("60", properties.getProperty("football-data.cache.live-ttl-seconds"));
        assertEquals("60", properties.getProperty("football-data.cache.idle-min-ttl-seconds"));
        assertEquals("3600", properties.getProperty("football-data.cache.idle-max-ttl-seconds"));
        assertEquals("300", properties.getProperty("football-data.cache.current-season-ttl-seconds"));
    }

    // ==================== Requêtes simultanées ====================

    @Test
    void twoSimultaneousRequestsOnTheSameKeyProduceASingleOutgoingCall() throws Exception {
        CountDownLatch firstCallStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstCall = new CountDownLatch(1);
        when(restTemplate.exchange(eq(COMPETITION_URI), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenAnswer(invocation -> {
                firstCallStarted.countDown();
                assertTrue(releaseFirstCall.await(5, TimeUnit.SECONDS));
                return new ResponseEntity<>("{\"id\":2015}", HttpStatus.OK);
            });
        AtomicReference<CompetitionResponse> first = new AtomicReference<>();
        AtomicReference<CompetitionResponse> second = new AtomicReference<>();

        Thread firstRequest = new Thread(() -> first.set(service.getCompetition("FL1", new HttpHeaders())));
        firstRequest.start();
        assertTrue(firstCallStarted.await(5, TimeUnit.SECONDS));

        // La seconde requête arrive pendant que la première attend football-data.org.
        Thread secondRequest = new Thread(() -> second.set(service.getCompetition("FL1", new HttpHeaders())));
        secondRequest.start();
        awaitBlocked(secondRequest);

        releaseFirstCall.countDown();
        firstRequest.join(5000);
        secondRequest.join(5000);

        assertEquals(2015L, first.get().id());
        assertSame(first.get(), second.get());
        verifyOutgoingCalls(COMPETITION_URI, 1);
        verify(rateLimiter, times(1)).tryAcquire(any());
    }

    /** Attend que le fil soit bloqué sur le chargement en cours (verrou de Caffeine). */
    private static void awaitBlocked(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.WAITING) {
            assertTrue(System.nanoTime() < deadline, "la seconde requête n'attend pas le chargement en cours");
            Thread.sleep(5);
        }
    }
}
