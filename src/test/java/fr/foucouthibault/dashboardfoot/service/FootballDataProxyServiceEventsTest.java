package fr.foucouthibault.dashboardfoot.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.foucouthibault.dashboardfoot.config.CacheConfig;
import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamErrorException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Événements nommés (champ {@code event}) écrits par le service : leurs noms et leurs champs
 * sont un contrat pour les recherches dans les logs de production, ils ne doivent pas changer
 * par mégarde. Les lignes sont lues sur un appender en mémoire, indépendant du format de sortie.
 */
class FootballDataProxyServiceEventsTest {

    private static final String URL = "https://api.football-data.org/v4/competitions/FL1";

    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final FootballDataRateLimiter rateLimiter = mock(FootballDataRateLimiter.class);
    private final Logger logger = (Logger) LoggerFactory.getLogger(FootballDataProxyService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private CaffeineCacheManager cacheManager;
    private FootballDataProxyService service;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        appender.start();
        logger.addAppender(appender);

        cacheManager = new CaffeineCacheManager(
            CacheConfig.COMPETITIONS_CACHE, CacheConfig.STATIC_CACHE, CacheConfig.CURRENT_SEASON_CACHE, CacheConfig.HISTORICAL_SEASON_CACHE,
            CacheConfig.fallbackCacheName(CacheConfig.COMPETITIONS_CACHE));
        service = new FootballDataProxyService(restTemplate, cacheManager, rateLimiter);
        setField("apiBaseUrl", "https://api.football-data.org/v4");
        setField("apiToken", "cle-secrete-de-test");
        when(rateLimiter.tryAcquire(any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    private void setField(String name, String value) throws ReflectiveOperationException {
        Field field = FootballDataProxyService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }

    private void upstreamAnswers(String body) {
        when(restTemplate.exchange(eq(URI.create(URL)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok(body));
    }

    private void upstreamThrows(RuntimeException e) {
        when(restTemplate.exchange(eq(URI.create(URL)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(e);
    }

    /** Champs clé-valeur de la ligne qui porte l'événement donné (une seule attendue). */
    private Map<String, Object> event(String name) {
        List<Map<String, Object>> matching = appender.list.stream()
            .map(FootballDataProxyServiceEventsTest::keyValues)
            .filter(fields -> name.equals(fields.get(FootballDataProxyService.EVENT)))
            .toList();
        assertThat(matching).as("événement %s", name).hasSize(1);
        return matching.getFirst();
    }

    private static Map<String, Object> keyValues(ILoggingEvent event) {
        List<KeyValuePair> pairs = event.getKeyValuePairs() == null ? List.of() : event.getKeyValuePairs();
        return pairs.stream().collect(Collectors.toMap(kv -> kv.key, kv -> kv.value));
    }

    @Test
    void rateLimiterRefusalProducesQuotaAtteintWithRetryAfter() {
        when(rateLimiter.tryAcquire(any())).thenReturn(false);
        when(rateLimiter.secondsUntilNextSlot(any())).thenReturn(17L);

        assertThrows(RateLimitExceededException.class, () -> service.getCompetition("FL1", new HttpHeaders()));

        assertThat(event("quota_atteint")).containsEntry("retryAfter", 17L).containsEntry("quotaCategory", "CURRENT");
    }

    @Test
    void upstreamErrorProducesAmontErreurWithItsStatus() {
        upstreamThrows(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(), null, null));

        assertThrows(UpstreamErrorException.class, () -> service.getCompetition("FL1", new HttpHeaders()));

        assertThat(event("amont_erreur")).containsEntry("upstreamStatus", 403);
    }

    @Test
    void networkFailureProducesAmontIndisponible() {
        upstreamThrows(new ResourceAccessException("Connection refused"));

        assertThrows(UpstreamUnavailableException.class, () -> service.getCompetition("FL1", new HttpHeaders()));

        event("amont_indisponible");
    }

    @Test
    void unreadableResponseProducesAmontIndisponible() {
        upstreamAnswers("pas du JSON");

        assertThrows(UpstreamUnavailableException.class, () -> service.getCompetition("FL1", new HttpHeaders()));

        event("amont_indisponible");
    }

    @Test
    void onlyACacheMissProducesCacheManque() {
        upstreamAnswers("{\"id\":2015,\"name\":\"Ligue 1\",\"code\":\"FL1\"}");

        service.getCompetition("FL1", new HttpHeaders());
        service.getCompetition("FL1", new HttpHeaders());

        assertThat(event("cache_manque"))
            .containsEntry("cache", CacheConfig.COMPETITIONS_CACHE)
            .containsEntry("cacheKey", "competition_FL1");
    }

    @Test
    void servingTheFallbackCopyProducesDonneePerimeeServieWithItsReasonAndAge() {
        when(restTemplate.exchange(eq(URI.create(URL)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"id\":2015}"))
            .thenThrow(new ResourceAccessException("Connection refused"));
        service.getCompetition("FL1", new HttpHeaders());
        // Entrée principale expirée, copie de secours encore là.
        cacheManager.getCache(CacheConfig.COMPETITIONS_CACHE).evict("competition_FL1");

        service.getCompetition("FL1", new HttpHeaders());

        assertThat(event("donnee_perimee_servie"))
            .containsEntry("cache", CacheConfig.COMPETITIONS_CACHE)
            .containsEntry("cacheKey", "competition_FL1")
            .containsEntry("reason", "amont_indisponible")
            .containsEntry("ageSeconds", 0L);
    }

    @Test
    void theFallbackReasonDistinguishesTheQuotaFromUpstreamFailures() {
        assertThat(FootballDataProxyService.fallbackReason(new RateLimitExceededException(5))).isEqualTo("quota_interne");
        assertThat(FootballDataProxyService.fallbackReason(new UpstreamErrorException(HttpStatus.TOO_MANY_REQUESTS)))
            .isEqualTo("amont_erreur");
        assertThat(FootballDataProxyService.fallbackReason(new UpstreamErrorException(HttpStatus.SERVICE_UNAVAILABLE)))
            .isEqualTo("amont_erreur");
        assertThat(FootballDataProxyService.fallbackReason(new UpstreamUnavailableException(null)))
            .isEqualTo("amont_indisponible");
        assertThat(FootballDataProxyService.fallbackReason(new UpstreamErrorException(HttpStatus.NOT_FOUND))).isNull();
    }

    /** Le jeton est envoyé à football-data.org, jamais écrit dans les logs. */
    @Test
    void apiKeyNeverAppearsInLogs() {
        upstreamThrows(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(), null, null));

        assertThrows(UpstreamErrorException.class, () -> service.getCompetition("FL1", new HttpHeaders()));

        assertThat(appender.list).isNotEmpty().allSatisfy(line -> {
            assertThat(line.getFormattedMessage()).doesNotContain("cle-secrete-de-test");
            assertThat(keyValues(line).values()).noneMatch(v -> String.valueOf(v).contains("cle-secrete-de-test"));
        });
    }
}
