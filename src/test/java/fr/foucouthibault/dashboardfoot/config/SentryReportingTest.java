package fr.foucouthibault.dashboardfoot.config;

import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamErrorException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUnavailableException;
import fr.foucouthibault.dashboardfoot.service.FootballDataProxyService;
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
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sentry activé (DSN factice), transport remplacé par {@link RecordingSentryTransport} : vérifie
 * quelles erreurs partent, avec quel environnement et quelle version, et que rien de personnel ni
 * de secret ne les accompagne. Le service est simulé : aucun appel à football-data.org.
 */
@SpringBootTest(properties = {
    "sentry.dsn=https://cle-publique@o0.ingest.sentry.io/0",
    "sentry.environment=production",
    "sentry.release=version-de-test",
    "football-data.api.token=" + SentryReportingTest.TOKEN,
})
@AutoConfigureMockMvc
@DirtiesContext
class SentryReportingTest {

    static final String TOKEN = "jeton-secret-de-test";
    private static final String ROUTE = "/api/competitions/FL1";
    private static final String CLIENT_IP = "203.0.113.7";

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

    @MockitoBean
    private FootballDataProxyService service;

    @BeforeEach
    void clearEvents() {
        transport.clear();
    }

    @AfterAll
    static void closeSentry() {
        // Le SDK est global : ne pas le laisser actif pour les autres classes de test.
        Sentry.close();
    }

    /** Requête portant des données personnelles qui ne doivent jamais atteindre Sentry. */
    private static MockHttpServletRequestBuilder requestWithPersonalData() {
        return get(ROUTE)
            .header("X-Forwarded-For", CLIENT_IP)
            .header(HttpHeaders.COOKIE, "session=cookie-secret")
            .header(HttpHeaders.AUTHORIZATION, "Bearer autorisation-secrete")
            .header(HttpHeaders.USER_AGENT, "navigateur-de-test")
            .with(request -> {
                request.setRemoteAddr(CLIENT_IP);
                return request;
            });
    }

    private void failWith(RuntimeException e) {
        doThrow(e).when(service).getCompetition(anyString(), any());
    }

    /** Types des exceptions de l'événement (la cause comprise), tels qu'envoyés à Sentry. */
    private static List<String> exceptionTypes(SentryEvent event) {
        return event.getExceptions().stream().map(SentryException::getType).toList();
    }

    private SentryEvent singleEvent() {
        List<RecordingSentryTransport.Recorded> events = transport.events();
        assertThat(events).hasSize(1);
        return events.getFirst().event();
    }

    @Test
    void unhandledExceptionIsSentWithEnvironmentAndRelease() {
        failWith(new IllegalStateException("panne de test"));

        assertThrows(Exception.class, () -> mockMvc.perform(requestWithPersonalData()));

        SentryEvent event = singleEvent();
        assertThat(exceptionTypes(event)).contains("IllegalStateException");
        assertThat(event.getEnvironment()).isEqualTo("production");
        assertThat(event.getRelease()).isEqualTo("version-de-test");
    }

    @Test
    void noPersonalDataNorKeyInTheEvent() {
        failWith(new IllegalStateException("panne de test"));

        assertThrows(Exception.class, () -> mockMvc.perform(requestWithPersonalData()));

        SentryEvent event = singleEvent();
        assertThat(event.getRequest()).isNotNull();
        assertThat(event.getRequest().getHeaders()).isNull();
        assertThat(event.getRequest().getCookies()).isNull();
        assertThat(event.getUser()).isNull();
        assertThat(transport.events().getFirst().json())
            .doesNotContain(CLIENT_IP, "cookie-secret", "autorisation-secrete", "navigateur-de-test", TOKEN);
    }

    @Test
    void upstreamUnavailableIsSent() throws Exception {
        failWith(new UpstreamUnavailableException(new IOException("connexion refusée")));

        mockMvc.perform(requestWithPersonalData()).andExpect(status().isBadGateway());

        assertThat(exceptionTypes(singleEvent())).contains("UpstreamUnavailableException");
    }

    @Test
    void tooManyRequestsFromFootballDataIsSent() throws Exception {
        failWith(new UpstreamErrorException(HttpStatus.TOO_MANY_REQUESTS));

        mockMvc.perform(requestWithPersonalData()).andExpect(status().isTooManyRequests());

        assertThat(exceptionTypes(singleEvent())).contains("UpstreamErrorException");
    }

    @Test
    void internalRateLimitIsNotSent() throws Exception {
        failWith(new RateLimitExceededException(42));

        mockMvc.perform(requestWithPersonalData()).andExpect(status().isTooManyRequests());

        assertThat(transport.events()).isEmpty();
    }

    @Test
    void relayedNotFoundAndForbiddenAreNotSent() throws Exception {
        failWith(new UpstreamErrorException(HttpStatus.NOT_FOUND));
        mockMvc.perform(requestWithPersonalData()).andExpect(status().isNotFound());

        failWith(new UpstreamErrorException(HttpStatus.FORBIDDEN));
        mockMvc.perform(requestWithPersonalData()).andExpect(status().isForbidden());

        assertThat(transport.events()).isEmpty();
    }

    @Test
    void invalidRequestIsNotSent() throws Exception {
        mockMvc.perform(get("/api/competitions/FL1/standings").param("season", "20xx"))
            .andExpect(status().isBadRequest());

        assertThat(transport.events()).isEmpty();
    }
}
