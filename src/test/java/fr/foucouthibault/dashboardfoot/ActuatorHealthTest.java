package fr.foucouthibault.dashboardfoot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Endpoint de santé interrogé par l'hébergeur : {@code /actuator/health} répond
 * {@code {"status":"UP"}} sans détails, n'appelle jamais football-data.org ({@link RestTemplate}
 * simulé, jamais sollicité), et aucun autre endpoint Actuator n'est exposé.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ActuatorHealthTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RestTemplate restTemplate;

    @Test
    void healthIsUpWithoutDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"status\":\"UP\"}", JsonCompareMode.STRICT));
    }

    @Test
    void healthNeverCallsFootballDataOrg() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void discoveryPageOnlyListsHealth() throws Exception {
        mockMvc.perform(get("/actuator"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$._links.keys()", containsInAnyOrder("self", "health", "health-path")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"info", "env", "beans", "configprops", "metrics", "mappings", "loggers",
        "heapdump", "threaddump", "caches", "shutdown"})
    void otherEndpointsAreNotExposed(String endpoint) throws Exception {
        mockMvc.perform(get("/actuator/" + endpoint))
            .andExpect(status().isNotFound());
    }

    /**
     * Les métriques Micrometer sont exclues d'Actuator (pom.xml) : elles apportaient HdrHistogram 2.2.2,
     * touché par CVE-2026-14686 sans version corrigée. Échoue si une dépendance le ramène.
     */
    @Test
    void hdrHistogramIsNotOnTheClasspath() {
        assertThatThrownBy(() -> Class.forName("org.HdrHistogram.Histogram"))
            .isInstanceOf(ClassNotFoundException.class);
    }
}
