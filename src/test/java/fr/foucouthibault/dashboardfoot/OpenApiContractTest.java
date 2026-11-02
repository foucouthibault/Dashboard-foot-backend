package fr.foucouthibault.dashboardfoot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrat publié par springdoc. {@link #versionedSpecificationIsUpToDate()} écrit la
 * spécification dans {@code openapi.json} (racine du dépôt, lue par le frontend) puis échoue
 * si le fichier versionné était différent : la CI détecte ainsi un contrat modifié sans que
 * {@code openapi.json} soit mis à jour. En local, il suffit de relancer les tests et de
 * committer le fichier régénéré. Aucun appel à football-data.org n'est fait.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiContractTest {

    private static final Path VERSIONED_SPEC = Path.of("openapi.json");
    private static final String COMPETITION = "/api/competitions/{id}";
    private static final String SCORERS = "/api/competitions/{id}/scorers";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void versionedSpecificationIsUpToDate() throws Exception {
        String generated = specification();
        String versioned = Files.exists(VERSIONED_SPEC)
            ? Files.readString(VERSIONED_SPEC, StandardCharsets.UTF_8) : "";

        Files.writeString(VERSIONED_SPEC, generated, StandardCharsets.UTF_8);

        assertThat(versioned)
            .as("openapi.json n'était pas à jour : il vient d'être régénéré, à vérifier et committer")
            .isEqualTo(generated);
    }

    @Test
    void describesTheFourRoutes() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paths.keys()", containsInAnyOrder(COMPETITION,
                COMPETITION + "/standings", COMPETITION + "/matches", SCORERS)));
    }

    @Test
    void describesTheParametersButNotTheRelayedHeaders() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath("$.paths['" + SCORERS + "'].get.parameters[*].name",
                containsInAnyOrder("id", "limit", "season")))
            .andExpect(jsonPath("$.paths['" + SCORERS + "'].get.parameters[?(@.name == 'id')].schema.pattern")
                .value("^[a-zA-Z0-9_-]+$"))
            .andExpect(jsonPath("$.paths['" + SCORERS + "'].get.parameters[?(@.name == 'season')].schema.pattern")
                .value("^\\d{4}$"));
    }

    @Test
    void describesTheErrorsAsProblemDetails() throws Exception {
        String responses = "$.paths['" + COMPETITION + "'].get.responses";
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath(responses + ".keys()", hasItems("200", "400", "403", "404", "429", "502", "default")))
            .andExpect(jsonPath(responses + "['400'].content['application/problem+json'].schema.$ref",
                startsWith("#/components/schemas/ProblemDetail")))
            .andExpect(jsonPath(responses + "['429'].headers['Retry-After']").exists());
    }

    @Test
    void marksResponseFieldsRequiredAndOnlyNullableOnesAsNullable() throws Exception {
        String schemas = "$.components.schemas";
        mockMvc.perform(get("/v3/api-docs"))
            .andExpect(jsonPath(schemas + ".CompetitionResponse.required",
                containsInAnyOrder("id", "name", "code", "emblem")))
            .andExpect(jsonPath(schemas + ".CompetitionResponse.properties.emblem.nullable").value(true))
            .andExpect(jsonPath(schemas + ".CompetitionResponse.properties.name.nullable").doesNotExist())
            .andExpect(jsonPath(schemas + ".StandingGroup.properties.type.enum",
                containsInAnyOrder("TOTAL", "HOME", "AWAY")))
            // Les champs d'un ProblemDetail (type, instance...) restent facultatifs.
            .andExpect(jsonPath(schemas + ".ProblemDetail.required").doesNotExist())
            .andExpect(jsonPath(schemas + ".ProblemDetail.properties.properties").doesNotExist());
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
            .andExpect(status().is3xxRedirection())
            .andExpect(header().string("Location", startsWith("/swagger-ui/index.html")));
    }

    private String specification() throws Exception {
        return mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
