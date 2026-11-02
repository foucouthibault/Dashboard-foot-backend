package fr.foucouthibault.dashboardfoot.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Description de l'API publiée par springdoc sur {@code /v3/api-docs} et {@code /swagger-ui.html}.
 * La spécification est aussi versionnée dans {@code openapi.json} (voir {@code OpenApiContractTest}),
 * d'où le frontend tire ses types.
 */
@Configuration
public class OpenApiConfig {

    /** Schéma des erreurs, dont les champs sont réellement facultatifs ({@code type}, {@code instance}). */
    static final String PROBLEM_DETAIL_SCHEMA = "ProblemDetail";

    @Bean
    public OpenAPI dashboardFootOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Dashboard Foot API")
                .version("v1")
                .description("""
                    Proxy vers football-data.org v4 : compétitions, classements, matchs et buteurs. \
                    Les réponses reprennent la structure de football-data.org, réduite aux champs lus \
                    par le frontend. Les erreurs sont des ProblemDetail (RFC 9457).""")
                .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
            // URL relative : la spécification ne dépend ni de l'hôte qui la sert ni de l'environnement.
            .servers(List.of(new Server().url("/")));
    }

    /**
     * Marque toutes les propriétés des réponses comme obligatoires : Jackson sérialise chaque
     * composant des records, même {@code null}. Un champ qui peut valoir {@code null} le dit par
     * {@code @Schema(nullable = true)} sur le record.
     *
     * Retire aussi {@code properties} du schéma ProblemDetail : c'est le getter des champs
     * additionnels, que Jackson aplatit à la racine ; il n'apparaît jamais tel quel dans le JSON.
     */
    @Bean
    public OpenApiCustomizer requiredRecordProperties() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            openApi.getComponents().getSchemas().forEach((name, schema) -> {
                if (PROBLEM_DETAIL_SCHEMA.equals(name)) {
                    if (schema.getProperties() != null) {
                        schema.getProperties().remove("properties");
                    }
                } else {
                    markAllPropertiesRequired(schema);
                }
            });
        };
    }

    private static void markAllPropertiesRequired(Schema<?> schema) {
        Map<String, Schema> properties = schema.getProperties();
        if (properties != null && !properties.isEmpty()) {
            schema.setRequired(new ArrayList<>(properties.keySet()));
        }
    }
}
