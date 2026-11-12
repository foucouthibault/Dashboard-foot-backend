package fr.foucouthibault.dashboardfoot.config;

import fr.foucouthibault.dashboardfoot.exception.UpstreamUnavailableException;
import fr.foucouthibault.dashboardfoot.service.FootballDataProxyService;
import io.sentry.Sentry;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Configuration par défaut (SENTRY_DSN absente) : le SDK reste désactivé, rien n'est envoyé. */
@SpringBootTest
@AutoConfigureMockMvc
class SentryDisabledTest {

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

    @BeforeAll
    static void startFromAClosedSdk() {
        Sentry.close();
    }

    @Test
    void sdkIsDisabledWithoutDsn() {
        assertThat(Sentry.isEnabled()).isFalse();
    }

    @Test
    void errorsAreNotSentWithoutDsn() throws Exception {
        doThrow(new UpstreamUnavailableException(new IOException("connexion refusée")))
            .when(service).getCompetition(anyString(), any());
        mockMvc.perform(get("/api/competitions/FL1")).andExpect(status().isBadGateway());

        doThrow(new IllegalStateException("panne de test")).when(service).getCompetition(anyString(), any());
        assertThrows(Exception.class, () -> mockMvc.perform(get("/api/competitions/FL1")));

        assertThat(transport.events()).isEmpty();
    }
}
