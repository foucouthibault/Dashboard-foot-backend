package fr.foucouthibault.dashboardfoot.config;

import fr.foucouthibault.dashboardfoot.service.FootballDataRateLimiter;
import io.sentry.Sentry;
import io.sentry.SentryEvent;
import io.sentry.protocol.SentryException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sentry et copie de secours (#76) : quand la copie masque une panne de football-data.org, le
 * visiteur reçoit une donnée mais l'incident part quand même vers Sentry ; quand elle remplace un
 * refus du limiteur interne, rien ne part. Service et caches réels, {@link RestTemplate} et
 * limiteur simulés, transport Sentry remplacé par {@link RecordingSentryTransport}.
 */
@SpringBootTest(properties = {
    "sentry.dsn=https://cle-publique@o0.ingest.sentry.io/0",
    "sentry.environment=production",
})
@AutoConfigureMockMvc
@DirtiesContext
class SentryFallbackReportingTest {

    private static final String ROUTE = "/api/competitions/FL1";

    @TestConfiguration
    static class TransportConfig {
        @Bean
        RecordingSentryTransport recordingSentryTransport() {
            return new RecordingSentryTransport();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingSentryTransport transport;

    @Autowired
    private CacheManager cacheManager;

    @MockitoBean
    private RestTemplate restTemplate;

    @MockitoBean
    private FootballDataRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        when(rateLimiter.tryAcquire()).thenReturn(true);
    }

    @AfterAll
    static void closeSentry() {
        // Le SDK est global : ne pas le laisser actif pour les autres classes de test.
        Sentry.close();
    }

    /** Première réponse réussie (copie créée), puis l'échec donné ; l'entrée principale expire. */
    private void copyExistsThenUpstream(RuntimeException failure) throws Exception {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok("{\"id\":2015,\"name\":\"Ligue 1\",\"code\":\"FL1\"}"))
            .thenThrow(failure);
        mockMvc.perform(get(ROUTE)).andExpect(status().isOk());
        List.of(CacheConfig.COMPETITIONS_CACHE, CacheConfig.STATIC_CACHE,
                CacheConfig.CURRENT_SEASON_CACHE, CacheConfig.HISTORICAL_SEASON_CACHE)
            .forEach(name -> cacheManager.getCache(name).clear());
        transport.clear();
    }

    private static List<String> exceptionTypes(SentryEvent event) {
        return event.getExceptions().stream().map(SentryException::getType).toList();
    }

    @Test
    void upstreamOutageMaskedByTheCopyIsStillSent() throws Exception {
        copyExistsThenUpstream(new ResourceAccessException("Connection refused"));

        mockMvc.perform(get(ROUTE))
            .andExpect(status().isOk())
            .andExpect(header().exists("Age"));

        assertThat(transport.events()).hasSize(1);
        assertThat(exceptionTypes(transport.events().getFirst().event())).contains("UpstreamUnavailableException");
    }

    @Test
    void tooManyRequestsFromFootballDataMaskedByTheCopyIsStillSent() throws Exception {
        copyExistsThenUpstream(new HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS));

        mockMvc.perform(get(ROUTE)).andExpect(status().isOk());

        assertThat(transport.events()).hasSize(1);
        assertThat(exceptionTypes(transport.events().getFirst().event())).contains("UpstreamErrorException");
    }

    @Test
    void internalRateLimitReplacedByTheCopyIsNotSent() throws Exception {
        copyExistsThenUpstream(new IllegalStateException("ne doit pas être appelé"));
        when(rateLimiter.tryAcquire()).thenReturn(false);

        mockMvc.perform(get(ROUTE))
            .andExpect(status().isOk())
            .andExpect(header().exists("Age"));

        assertThat(transport.events()).isEmpty();
    }
}
