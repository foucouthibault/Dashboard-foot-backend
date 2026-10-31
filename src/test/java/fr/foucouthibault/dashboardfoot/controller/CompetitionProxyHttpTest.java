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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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
 * des tests séparés pour que le passage à ProblemDetail (#35) n'ait à adapter que
 * les assertions de corps.
 */
@WebMvcTest(CompetitionProxyController.class)
@Import({FootballDataProxyService.class, CacheConfig.class})
class CompetitionProxyHttpTest {

    private static final String BASE = "https://api.football-data.org/v4";

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
    void competition_exposesTheFieldsReadByTheFrontend() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1", fixture("competition.json"));

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(jsonPath("$.id").value(2015))
            .andExpect(jsonPath("$.name").value("Ligue 1"))
            .andExpect(jsonPath("$.code").value("FL1"))
            .andExpect(jsonPath("$.emblem").exists())
            .andExpect(jsonPath("$.area.name").value("France"))
            .andExpect(jsonPath("$.currentSeason.startDate").exists())
            .andExpect(jsonPath("$.currentSeason.endDate").exists())
            .andExpect(jsonPath("$.currentSeason.currentMatchday").value(10));
    }

    @Test
    void standings_returns200WithJsonContentType() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/standings", fixture("standings.json"));

        mockMvc.perform(get("/api/competitions/FL1/standings"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("application/json")));
    }

    @Test
    void standings_exposesTheFieldsReadByTheFrontend() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/standings", fixture("standings.json"));

        mockMvc.perform(get("/api/competitions/FL1/standings"))
            .andExpect(jsonPath("$.competition.name").value("Ligue 1"))
            .andExpect(jsonPath("$.season.currentMatchday").value(10))
            .andExpect(jsonPath("$.standings[0].type").value("TOTAL"))
            .andExpect(jsonPath("$.standings[0].table", hasSize(2)))
            .andExpect(jsonPath("$.standings[0].table[0].position").value(1))
            .andExpect(jsonPath("$.standings[0].table[0].team.name").value("Paris Saint-Germain FC"))
            .andExpect(jsonPath("$.standings[0].table[0].team.shortName").value("PSG"))
            .andExpect(jsonPath("$.standings[0].table[0].team.tla").value("PSG"))
            .andExpect(jsonPath("$.standings[0].table[0].team.crest").exists())
            .andExpect(jsonPath("$.standings[0].table[0].playedGames").value(10))
            .andExpect(jsonPath("$.standings[0].table[0].form").exists())
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
    void matches_exposesTheFieldsReadByTheFrontend() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/matches", fixture("matches.json"));

        mockMvc.perform(get("/api/competitions/FL1/matches"))
            .andExpect(jsonPath("$.resultSet.count").value(2))
            .andExpect(jsonPath("$.matches", hasSize(2)))
            .andExpect(jsonPath("$.matches[0].id").value(497410))
            .andExpect(jsonPath("$.matches[0].utcDate").exists())
            .andExpect(jsonPath("$.matches[0].status").value("FINISHED"))
            .andExpect(jsonPath("$.matches[0].matchday").value(1))
            .andExpect(jsonPath("$.matches[0].homeTeam.name").exists())
            .andExpect(jsonPath("$.matches[0].homeTeam.crest").exists())
            .andExpect(jsonPath("$.matches[0].awayTeam.tla").value("OM"))
            .andExpect(jsonPath("$.matches[0].score.winner").value("HOME_TEAM"))
            .andExpect(jsonPath("$.matches[0].score.fullTime.home").value(2))
            .andExpect(jsonPath("$.matches[0].score.fullTime.away").value(1))
            .andExpect(jsonPath("$.matches[1].score.fullTime.home").doesNotExist());
    }

    @Test
    void scorers_returns200WithJsonContentType() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/scorers?limit=50", fixture("scorers.json"));

        mockMvc.perform(get("/api/competitions/FL1/scorers"))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Type", containsString("application/json")));
    }

    @Test
    void scorers_exposesTheFieldsReadByTheFrontend() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/scorers?limit=50", fixture("scorers.json"));

        mockMvc.perform(get("/api/competitions/FL1/scorers"))
            .andExpect(jsonPath("$.count").value(8))
            .andExpect(jsonPath("$.scorers", hasSize(8)))
            .andExpect(jsonPath("$.scorers[0].player.id").value(1000))
            .andExpect(jsonPath("$.scorers[0].player.name").value("Joueur 0"))
            .andExpect(jsonPath("$.scorers[0].player.nationality").value("France"))
            .andExpect(jsonPath("$.scorers[0].player.position").exists())
            .andExpect(jsonPath("$.scorers[0].team.name").exists())
            .andExpect(jsonPath("$.scorers[0].team.crest").exists())
            .andExpect(jsonPath("$.scorers[0].playedMatches").value(10))
            .andExpect(jsonPath("$.scorers[0].goals").value(12))
            .andExpect(jsonPath("$.scorers[0].assists").value(3))
            .andExpect(jsonPath("$.scorers[0].penalties").value(2));
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
        upstreamReturns(BASE + "/competitions/FL1/scorers?limit=50&season=2023", fixture("scorers.json"));

        mockMvc.perform(get("/api/competitions/FL1/scorers").param("season", "2023"))
            .andExpect(status().isOk());

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertThat(uri.getValue().getQuery()).contains("season=2023");
    }

    @Test
    void standingsWithSeason_forwardsTheSeasonToUpstream() throws Exception {
        upstreamReturns(BASE + "/competitions/FL1/standings?season=2023", fixture("standings.json"));

        mockMvc.perform(get("/api/competitions/FL1/standings").param("season", "2023"))
            .andExpect(status().isOk());

        ArgumentCaptor<URI> uri = ArgumentCaptor.forClass(URI.class);
        verify(restTemplate).exchange(uri.capture(), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertThat(uri.getValue().getQuery()).isEqualTo("season=2023");
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

    @Test
    void invalidId_bodyDescribesTheError() throws Exception {
        mockMvc.perform(get("/api/competitions/FL1$"))
            .andExpect(jsonPath("$.error").value("Invalid competition ID format"));
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
    void invalidSeason_bodyDescribesTheError() throws Exception {
        mockMvc.perform(get("/api/competitions/FL1/standings").param("season", "abcd"))
            .andExpect(jsonPath("$.error").value("Invalid season format"));
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
            .andExpect(header().string("Content-Type", containsString("application/json")));
    }

    @Test
    void rateLimitExceeded_bodyDescribesTheError() throws Exception {
        when(rateLimiter.tryAcquire()).thenReturn(false);
        when(rateLimiter.secondsUntilNextSlot()).thenReturn(42L);

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(jsonPath("$.error").exists());
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
    void upstreamError_keepsTheUpstreamContentType() throws Exception {
        upstreamForbidden();

        mockMvc.perform(get("/api/competitions/FL1/standings"))
            .andExpect(header().string("Content-Type", containsString("application/json")));
    }

    @Test
    void upstreamError_keepsTheSameBody() throws Exception {
        upstreamForbidden();

        mockMvc.perform(get("/api/competitions/FL1/standings"))
            .andExpect(content().json("{\"message\":\"Restricted resource\",\"errorCode\":403}", true));
    }

    // ==================== Panne réseau (502) ====================

    @Test
    void networkFailure_returns502() throws Exception {
        upstreamFails(new ResourceAccessException("Connection refused: secret-internal-host:1234"));

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(status().isBadGateway());
    }

    @Test
    void networkFailure_doesNotExposeTheExceptionMessage() throws Exception {
        upstreamFails(new ResourceAccessException("Connection refused: secret-internal-host:1234"));

        mockMvc.perform(get("/api/competitions/FL1"))
            .andExpect(jsonPath("$.error").value("Failed to fetch data from API"))
            .andExpect(content().string(not(containsString("secret-internal-host"))));
    }
}
