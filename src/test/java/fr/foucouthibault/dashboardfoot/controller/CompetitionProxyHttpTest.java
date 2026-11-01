package fr.foucouthibault.dashboardfoot.controller;

import fr.foucouthibault.dashboardfoot.config.CacheConfig;
import fr.foucouthibault.dashboardfoot.service.FootballDataProxyService;
import fr.foucouthibault.dashboardfoot.service.FootballDataRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Year;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests de caractérisation de la couche HTTP : ils figent ce que voit le frontend
 * (statut, en-têtes, corps) pour les 4 routes. Le service et le cache sont réels,
 * seuls {@link RestTemplate} (pas d'appel réseau) et le limiteur de débit (pas de
 * dépendance à l'heure) sont simulés. Statut, en-têtes et corps sont vérifiés dans
 * des tests séparés.
 *
 * Depuis #35, toutes les erreurs sont des ProblemDetail (RFC 9457) en
 * {@code application/problem+json} : {@code title}, {@code status}, {@code detail}
 * ({@code type} omis, donc {@code about:blank}). Les statuts et l'en-tête {@code Retry-After} n'ont pas changé.
 *
 * Depuis #34, les réponses sont typées : seuls les champs déclarés dans les types du
 * frontend ({@code Dashboard-foot/src/types/index.ts}) sont renvoyés, plus {@code count}
 * pour les buteurs. Les tests « exposesOnlyTheFieldsReadByTheFrontend » vérifient à la
 * fois leur présence et l'absence d'un échantillon de champs amont non lus.
 */
@WebMvcTest(CompetitionProxyController.class)
@Import({FootballDataProxyService.class, CacheConfig.class})
class CompetitionProxyHttpTest {

    private static final String BASE = "https://api.football-data.org/v4";
    /** Saison terminée, calculée comme dans FootballDataProxyServiceTest. */
    private static final String HISTORICAL_SEASON = String.valueOf(Year.now().getValue() - 5);
    /** En-tête Accept envoyé par défaut par axios, donc par le frontend. */
    private static final String FRONTEND_ACCEPT = "application/json, text/plain, */*";
    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private MockMvc mockMvc;

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

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("fixtures/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private void upstreamReturns(String url, String body) {
        when(restTemplate.exchange(eq(URI.create(url)), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private void upstreamFails(Throwable failure) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(failure);
    }

    // ==================== Cas nominal des 4 routes ====================

    @Test
    void competition_returns200WithJsonContentType() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1", fixture("competition.json"));

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("application/json")));
    }

    @Test
    void competition_exposesOnlyTheFieldsReadByTheFrontend() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1", fixture("competition.json"));

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(jsonPath("$.id").value(2015))
            .andExpect(jsonPath("$.name").value("Ligue 1"))
            .andExpect(jsonPath("$.code").value("FL1"))
            .andExpect(jsonPath("$.emblem").value("https://crests.football-data.org/FL1.png"))
            .andExpect(jsonPath("$.type").doesNotExist())
            .andExpect(jsonPath("$.area").doesNotExist())
            .andExpect(jsonPath("$.currentSeason").doesNotExist());
    }

    @Test
    void standings_returns200WithJsonContentType() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/standings", fixture("standings.json"));

        mockMvc.perform(get("/api/competitions/FL1/standings"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("application/json")));
    }

    @Test
    void standings_exposesOnlyTheFieldsReadByTheFrontend() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/standings", fixture("standings.json"));

        mockMvc.perform(get("/api/competitions/FL1/standings"))
            .andExpect(jsonPath("$.competition").doesNotExist())
            .andExpect(jsonPath("$.season").doesNotExist())
            .andExpect(jsonPath("$.standings[0].type").value("TOTAL"))
            .andExpect(jsonPath("$.standings[0].stage").value("REGULAR_SEASON"))
            .andExpect(jsonPath("$.standings[0].group").value(nullValue()))
            .andExpect(jsonPath("$.standings[0].table", hasSize(2)))
            .andExpect(jsonPath("$.standings[0].table[0].position").value(1))
            .andExpect(jsonPath("$.standings[0].table[0].team.id").value(524))
            .andExpect(jsonPath("$.standings[0].table[0].team.name").value("Paris Saint-Germain FC"))
            .andExpect(jsonPath("$.standings[0].table[0].team.crest").value("https://crests.football-data.org/524.png"))
            .andExpect(jsonPath("$.standings[0].table[0].team.shortName").doesNotExist())
            .andExpect(jsonPath("$.standings[0].table[0].team.tla").doesNotExist())
            .andExpect(jsonPath("$.standings[0].table[0].playedGames").value(10))
            .andExpect(jsonPath("$.standings[0].table[0].form").doesNotExist())
            .andExpect(jsonPath("$.standings[0].table[0].won").value(8))
            .andExpect(jsonPath("$.standings[0].table[0].draw").value(1))
            .andExpect(jsonPath("$.standings[0].table[0].lost").value(1))
            .andExpect(jsonPath("$.standings[0].table[0].points").value(25))
            .andExpect(jsonPath("$.standings[0].table[0].goalsFor").value(25))
            .andExpect(jsonPath("$.standings[0].table[0].goalsAgainst").value(8))
            .andExpect(jsonPath("$.standings[0].table[0].goalDifference").value(17));
    }

    @Test
    void matches_returns200WithJsonContentType() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/matches", fixture("matches.json"));

        mockMvc.perform(get("/api/competitions/FL1/matches"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("application/json")));
    }

    @Test
    void matches_exposesOnlyTheFieldsReadByTheFrontend() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/matches", fixture("matches.json"));

        mockMvc.perform(get("/api/competitions/FL1/matches"))
            .andExpect(jsonPath("$.resultSet").doesNotExist())
            .andExpect(jsonPath("$.competition").doesNotExist())
            .andExpect(jsonPath("$.matches", hasSize(2)))
            .andExpect(jsonPath("$.matches[0].id").value(497410))
            .andExpect(jsonPath("$.matches[0].utcDate").exists())
            .andExpect(jsonPath("$.matches[0].status").value("FINISHED"))
            .andExpect(jsonPath("$.matches[0].matchday").value(1))
            .andExpect(jsonPath("$.matches[0].homeTeam.name").exists())
            .andExpect(jsonPath("$.matches[0].homeTeam.id").value(524))
            .andExpect(jsonPath("$.matches[0].homeTeam.crest").exists())
            .andExpect(jsonPath("$.matches[0].awayTeam.tla").doesNotExist())
            .andExpect(jsonPath("$.matches[0].awayTeam.id").value(516))
            .andExpect(jsonPath("$.matches[0].awayTeam.name").value("Olympique de Marseille"))
            .andExpect(jsonPath("$.matches[0].awayTeam.crest").exists())
            .andExpect(jsonPath("$.matches[0].score.winner").doesNotExist())
            .andExpect(jsonPath("$.matches[0].score.halfTime").doesNotExist())
            .andExpect(jsonPath("$.matches[0].stage").doesNotExist())
            .andExpect(jsonPath("$.matches[0].score.fullTime.home").value(2))
            .andExpect(jsonPath("$.matches[0].score.fullTime.away").value(1))
            // Match pas encore joué : score null, transmis tel quel (le frontend attend `number | null`)
            .andExpect(jsonPath("$.matches[1].score.fullTime.home").value(nullValue()));
    }

    @Test
    void scorers_returns200WithJsonContentType() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/scorers?limit=50", fixture("scorers.json"));

        mockMvc.perform(get("/api/competitions/FL1/scorers"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("application/json")));
    }

    @Test
    void scorers_exposesOnlyTheFieldsReadByTheFrontend() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/scorers?limit=50", fixture("scorers.json"));

        mockMvc.perform(get("/api/competitions/FL1/scorers"))
            .andExpect(jsonPath("$.count").value(8))
            .andExpect(jsonPath("$.scorers", hasSize(8)))
            .andExpect(jsonPath("$.scorers[0].player.id").value(1000))
            .andExpect(jsonPath("$.scorers[0].player.name").value("Joueur 0"))
            .andExpect(jsonPath("$.scorers[0].player.nationality").value("France"))
            .andExpect(jsonPath("$.scorers[0].player.position").doesNotExist())
            .andExpect(jsonPath("$.scorers[0].player.dateOfBirth").doesNotExist())
            .andExpect(jsonPath("$.scorers[0].team.name").value("Paris Saint-Germain FC"))
            .andExpect(jsonPath("$.scorers[0].team.id").value(524))
            .andExpect(jsonPath("$.scorers[0].team.crest").value("https://crests.football-data.org/524.png"))
            .andExpect(jsonPath("$.scorers[0].playedMatches").doesNotExist())
            .andExpect(jsonPath("$.competition").doesNotExist())
            .andExpect(jsonPath("$.season").doesNotExist())
            .andExpect(jsonPath("$.scorers[0].goals").value(12))
            .andExpect(jsonPath("$.scorers[0].assists").value(3))
            .andExpect(jsonPath("$.scorers[0].penalties").value(2));
    }

    // ==================== Cache et quota ====================

    @Test
    void cacheHit_doesNotCallUpstreamNorConsumeTheQuota() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1", fixture("competition.json"));

        mockMvc.perform(get("/api/competitions/FL1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/competitions/FL1")).andExpect(status().isOk());

        verify(restTemplate, times(1)).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        verify(rateLimiter, times(1)).tryAcquire();
    }

    @Test
    void scorersWithDifferentLimits_shareOneUpstreamCallAndOneQuotaSlot() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/scorers?limit=50", fixture("scorers.json"));

        mockMvc.perform(get("/api/competitions/FL1/scorers").param("limit", "5"))
            .andExpect(jsonPath("$.scorers", hasSize(5)));
        mockMvc.perform(get("/api/competitions/FL1/scorers").param("limit", "3"))
            .andExpect(jsonPath("$.scorers", hasSize(3)));

        verify(restTemplate, times(1)).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        verify(rateLimiter, times(1)).tryAcquire();
    }

    // ==================== Buteurs : limit et season ====================

    @Test
    void scorersWithLimit_returnsOnlyThatManyScorersAndUpdatesCount() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/scorers?limit=50", fixture("scorers.json"));

        mockMvc.perform(get("/api/competitions/FL1/scorers").param("limit", "5"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scorers", hasSize(5)))
            .andExpect(jsonPath("$.count").value(5))
            .andExpect(jsonPath("$.scorers[4].player.id").value(1004));
    }

    @Test
    void scorersWithLimit_stillAsksUpstreamForTheMaximumLimit() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/scorers?limit=50", fixture("scorers.json"));

        mockMvc.perform(get("/api/competitions/FL1/scorers").param("limit", "5"))
            .andExpect(status().isOk());

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertThat(uri.getValue().getQuery()).isEqualTo("limit=50");
    }

    @Test
    void scorersWithSeason_forwardsTheSeasonToUpstream() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/scorers?limit=50&season=" + HISTORICAL_SEASON, fixture("scorers.json"));

        mockMvc.perform(get("/api/competitions/FL1/scorers").param("season", HISTORICAL_SEASON))
            .andExpect(status().isOk());

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertThat(uri.getValue().getQuery()).contains("season=" + HISTORICAL_SEASON);
    }

    @Test
    void standingsWithSeason_forwardsTheSeasonToUpstream() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/standings?season=" + HISTORICAL_SEASON, fixture("standings.json"));

        mockMvc.perform(get("/api/competitions/FL1/standings").param("season", HISTORICAL_SEASON))
            .andExpect(status().isOk());

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertThat(uri.getValue().getQuery()).isEqualTo("season=" + HISTORICAL_SEASON);
    }

    // ==================== id invalide ====================

    @Test
    void invalidId_returns400_onEveryRoute() throws Exception {
        for (String path : new String[]{"", "/standings", "/matches", "/scorers"}) {
            mockMvc.perform(get("/api/competitions/FL1$" + path))
                .andExpect(status().isBadRequest());
        }
        verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    /*
     * Avant #34, les 400 et le 502 ne fixaient pas de Content-Type : sans en-tête Accept,
     * un corps JSON partait en text/plain. Depuis #35, toutes les erreurs partent en
     * application/problem+json, quel que soit l'en-tête Accept.
     */
    @Test
    void invalidId_contentTypeIsAlwaysProblemJson() throws Exception {
        mockMvc.perform(get("/api/competitions/FL1$").header("Accept", FRONTEND_ACCEPT))
            .andExpect(header().string("Content-Type", PROBLEM_JSON));
        mockMvc.perform(get("/api/competitions/FL1$"))
            .andExpect(header().string("Content-Type", PROBLEM_JSON));
    }

    /** {@code type} absent vaut {@code about:blank} (RFC 9457, § 4.2.1) : Spring l'omet. */
    @Test
    void invalidId_bodyIsAProblemDetail() throws Exception {
        mockMvc.perform(get("/api/competitions/FL1$"))
            .andExpect(jsonPath("$.type").doesNotExist())
            .andExpect(jsonPath("$.title").value("Bad Request"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("Format d'identifiant de compétition invalide"))
            .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void invalidId_doesNotConsumeTheQuota() throws Exception {
        mockMvc.perform(get("/api/competitions/FL1$")).andExpect(status().isBadRequest());

        verify(rateLimiter, never()).tryAcquire();
    }

    // ==================== season invalide ====================

    @Test
    void invalidSeason_returns400_onStandingsAndScorers() throws Exception {
        mockMvc.perform(get("/api/competitions/FL1/standings").param("season", "20x3"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/competitions/FL1/scorers").param("season", "20234"))
            .andExpect(status().isBadRequest());
        verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void invalidSeason_contentTypeIsAlwaysProblemJson() throws Exception {
        mockMvc.perform(get("/api/competitions/FL1/standings").param("season", "abcd")
                .header("Accept", FRONTEND_ACCEPT))
            .andExpect(header().string("Content-Type", PROBLEM_JSON));
        mockMvc.perform(get("/api/competitions/FL1/standings").param("season", "abcd"))
            .andExpect(header().string("Content-Type", PROBLEM_JSON));
    }

    @Test
    void invalidSeason_bodyIsAProblemDetail() throws Exception {
        mockMvc.perform(get("/api/competitions/FL1/standings").param("season", "abcd"))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.detail").value("Format de saison invalide"));
    }

    // ==================== Paramètre mal typé (erreur levée par Spring MVC) ====================

    @Test
    void nonNumericLimit_returns400AsAProblemDetailWithoutCallingUpstream() throws Exception {
        mockMvc.perform(get("/api/competitions/FL1/scorers").param("limit", "abc")
                .header("Accept", FRONTEND_ACCEPT))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("Content-Type", PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.title").value("Bad Request"));

        verify(rateLimiter, never()).tryAcquire();
    }

    // ==================== Quota atteint ====================

    @Test
    void rateLimitExceeded_returns429() throws Exception {
        when(rateLimiter.tryAcquire()).thenReturn(false);
        when(rateLimiter.secondsUntilNextSlot()).thenReturn(42L);

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(status().isTooManyRequests());
        verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void rateLimitExceeded_sendsRetryAfterHeader() throws Exception {
        when(rateLimiter.tryAcquire()).thenReturn(false);
        when(rateLimiter.secondsUntilNextSlot()).thenReturn(42L);

        mockMvc.perform(get("/api/competitions/FL1/matches"))
            .andExpect(header().string("Retry-After", "42"))
            .andExpect(header().string("Content-Type", PROBLEM_JSON));
    }

    @Test
    void rateLimitExceeded_bodyIsAProblemDetail() throws Exception {
        when(rateLimiter.tryAcquire()).thenReturn(false);
        when(rateLimiter.secondsUntilNextSlot()).thenReturn(42L);

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(jsonPath("$.title").value("Too Many Requests"))
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.detail").value(
                "Trop de requêtes vers l'API football-data.org, réessayez dans quelques secondes"));
    }

    // ==================== Erreur amont (403) ====================

    private void upstreamForbidden() {
        HttpHeaders upstreamHeaders = new HttpHeaders();
        upstreamHeaders.setContentType(MediaType.APPLICATION_JSON);
        upstreamFails(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", upstreamHeaders,
            "{\"message\":\"Restricted resource\",\"errorCode\":403}".getBytes(StandardCharsets.UTF_8),
            StandardCharsets.UTF_8));
    }

    @Test
    void upstreamError_keepsTheSameStatus() throws Exception {
        upstreamForbidden();

        mockMvc.perform(get("/api/competitions/FL1/standings"))
            .andExpect(status().isForbidden());
    }

    @Test
    void upstreamError_contentTypeIsProblemJson() throws Exception {
        upstreamForbidden();

        mockMvc.perform(get("/api/competitions/FL1/standings").header("Accept", FRONTEND_ACCEPT))
            .andExpect(header().string("Content-Type", PROBLEM_JSON));
    }

    /*
     * Avant #35, le corps de football-data.org était relayé tel quel. Il ne part plus que
     * dans les logs : le client reçoit un ProblemDetail portant le statut amont.
     */
    @Test
    void upstreamError_bodyIsAProblemDetailWithoutTheUpstreamBody() throws Exception {
        upstreamForbidden();

        mockMvc.perform(get("/api/competitions/FL1/standings"))
            .andExpect(jsonPath("$.title").value("Forbidden"))
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.detail").value("L'API football-data.org a répondu par une erreur"))
            .andExpect(content().string(not(containsString("Restricted resource"))));
    }

    @Test
    void upstreamServerError_keepsTheSameStatus() throws Exception {
        upstreamFails(HttpServerErrorException.create(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable",
            new HttpHeaders(), new byte[0], StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value(503));
    }

    // ==================== Panne réseau (502) ====================

    @Test
    void networkFailure_returns502() throws Exception {
        upstreamFails(new ResourceAccessException("Connection refused: secret-internal-host:1234"));

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(status().isBadGateway());
    }

    @Test
    void networkFailure_contentTypeIsAlwaysProblemJson() throws Exception {
        upstreamFails(new ResourceAccessException("Connection refused"));

        mockMvc.perform(get("/api/competitions/FL1").header("Accept", FRONTEND_ACCEPT))
            .andExpect(header().string("Content-Type", PROBLEM_JSON));
        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(header().string("Content-Type", PROBLEM_JSON));
    }

    @Test
    void networkFailure_doesNotExposeTheExceptionMessage() throws Exception {
        upstreamFails(new ResourceAccessException("Connection refused: secret-internal-host:1234"));

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(jsonPath("$.status").value(502))
            .andExpect(jsonPath("$.detail").value("Échec de l'appel à l'API football-data.org"))
            .andExpect(content().string(not(containsString("secret-internal-host"))));
    }

    // ==================== Réponse amont réussie mais inexploitable ====================

    @Test
    void upstreamSuccessWithUnreadableBody_returns502AndIsNotCached() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1", "ceci n'est pas du JSON");

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(status().isBadGateway())
            .andExpect(header().string("Content-Type", PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("Échec de l'appel à l'API football-data.org"));
        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(status().isBadGateway());

        verify(restTemplate, times(2)).exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    // ==================== En-têtes de la réponse amont ====================

    @Test
    void upstreamResponseHeaders_areNotRelayed() throws Exception {
        when(restTemplate.exchange(eq(URI.create(BASE + "/competitions/FL1")), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Authenticated-Client", "compte-football-data")
                .header("X-Requests-Available-Minute", "9")
                .body(fixture("competition.json")));

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist("X-Authenticated-Client"))
            .andExpect(header().doesNotExist("X-Requests-Available-Minute"));
    }
}
