package fr.foucouthibault.dashboardfoot.config;

import fr.foucouthibault.dashboardfoot.controller.CompetitionProxyController;
import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import fr.foucouthibault.dashboardfoot.model.CompetitionResponse;
import fr.foucouthibault.dashboardfoot.service.FootballDataProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS sur {@code /api/**} : seules les origines de {@code app.cors.allowed-origins} reçoivent
 * {@code Access-Control-Allow-Origin}, en {@code GET} uniquement. Le service est simulé : une
 * requête refusée ne doit jamais l'atteindre (donc jamais consommer le quota).
 */
@WebMvcTest(CompetitionProxyController.class)
@TestPropertySource(properties = "app.cors.allowed-origins=https://front.example, https://autre.example")
class CorsConfigTest {

    private static final String ROUTE = "/api/competitions/FL1";
    private static final String ALLOWED = "https://front.example";
    private static final String NOT_ALLOWED = "https://malveillant.example";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FootballDataProxyService service;

    @BeforeEach
    void stubService() {
        when(service.getCompetition(anyString(), any()))
            .thenReturn(new CompetitionResponse(2015L, "Ligue 1", "FL1", null));
    }

    @Test
    void allowedOriginReceivesTheCorsHeader() throws Exception {
        mockMvc.perform(get(ROUTE).header(HttpHeaders.ORIGIN, ALLOWED))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED));
    }

    @Test
    void everyOriginOfTheListIsAllowedAndSpacesAreIgnored() throws Exception {
        mockMvc.perform(get(ROUTE).header(HttpHeaders.ORIGIN, "https://autre.example"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://autre.example"));
    }

    /** Sans Access-Control-Expose-Headers, le navigateur masque Retry-After au frontend. */
    @Test
    void rateLimitedResponseExposesRetryAfterToAnAllowedOrigin() throws Exception {
        when(service.getCompetition(anyString(), any())).thenThrow(new RateLimitExceededException(42));

        mockMvc.perform(get(ROUTE).header(HttpHeaders.ORIGIN, ALLOWED))
            .andExpect(status().isTooManyRequests())
            .andExpect(header().string(HttpHeaders.RETRY_AFTER, "42"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                HttpHeaders.RETRY_AFTER + ", " + RequestIdFilter.HEADER));
    }

    /** Le frontend lit X-Request-Id pour citer la requête (logs du backend) dans ses erreurs. */
    @Test
    void responseExposesTheRequestIdToAnAllowedOrigin() throws Exception {
        mockMvc.perform(get(ROUTE).header(HttpHeaders.ORIGIN, ALLOWED).header(RequestIdFilter.HEADER, "id-du-front"))
            .andExpect(status().isOk())
            .andExpect(header().string(RequestIdFilter.HEADER, "id-du-front"))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
            .andExpect(header().stringValues(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                hasItem(containsString(RequestIdFilter.HEADER))));
    }

    @Test
    void notAllowedOriginIsRejectedWithoutCallingTheService() throws Exception {
        mockMvc.perform(get(ROUTE).header(HttpHeaders.ORIGIN, NOT_ALLOWED))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        verify(service, never()).getCompetition(anyString(), any());
    }

    @Test
    void preflightAllowsGetForAnAllowedOrigin() throws Exception {
        mockMvc.perform(options(ROUTE)
                .header(HttpHeaders.ORIGIN, ALLOWED)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET"));
    }

    /** En-têtes de suivi ajoutés par le SDK Sentry du frontend (issue frontend #136). */
    @Test
    void preflightAcceptsSentryTracingHeaders() throws Exception {
        mockMvc.perform(options(ROUTE)
                .header(HttpHeaders.ORIGIN, ALLOWED)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "sentry-trace, baggage"))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "sentry-trace, baggage"));
    }

    @Test
    void preflightRejectsAnUnknownHeader() throws Exception {
        mockMvc.perform(options(ROUTE)
                .header(HttpHeaders.ORIGIN, ALLOWED)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-en-tete-inconnu"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflightRejectsAnotherMethod() throws Exception {
        mockMvc.perform(options(ROUTE)
                .header(HttpHeaders.ORIGIN, ALLOWED)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
            .andExpect(status().isForbidden())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    /** Aucune route POST : Spring répond 405 avant même le contrôle CORS. */
    @Test
    void crossOriginPostIsRejected() throws Exception {
        mockMvc.perform(post(ROUTE).header(HttpHeaders.ORIGIN, ALLOWED))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        verify(service, never()).getCompetition(anyString(), any());
    }

    @Test
    void requestWithoutOriginIsUnaffected() throws Exception {
        mockMvc.perform(get(ROUTE))
            .andExpect(status().isOk())
            .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    /** Valeur par défaut d'application.properties : liste vide. */
    @Nested
    @TestPropertySource(properties = "app.cors.allowed-origins=")
    class WithoutConfiguration {

        @Test
        void noExternalOriginIsAllowed() throws Exception {
            mockMvc.perform(get(ROUTE).header(HttpHeaders.ORIGIN, ALLOWED))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }

        @Test
        void preflightIsNotAllowed() throws Exception {
            mockMvc.perform(options(ROUTE)
                    .header(HttpHeaders.ORIGIN, ALLOWED)
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }
}
