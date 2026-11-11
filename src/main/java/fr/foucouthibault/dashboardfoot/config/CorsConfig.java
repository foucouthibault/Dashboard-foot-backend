package fr.foucouthibault.dashboardfoot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;
import java.util.List;

/**
 * Autorise le frontend, hébergé sur un autre domaine, à appeler {@code /api/**} en {@code GET}.
 * Les origines viennent de {@code app.cors.allowed-origins} (liste séparée par des virgules,
 * variable {@code APP_CORS_ALLOWED_ORIGINS}). Liste vide (défaut) : aucune règle CORS, donc
 * aucune origine externe autorisée ; le frontend en développement passe par le proxy Vite.
 *
 * {@code Retry-After} (réponse {@code 429}) est exposé : sans cela, le navigateur le masque au
 * frontend, qui ne peut pas annoncer le délai d'attente.
 *
 * Avec une règle, Spring répond {@code 403} à une requête dont l'origine n'est pas dans la
 * liste, avant d'atteindre le contrôleur : elle ne consomme pas le quota de football-data.org.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    private final List<String> allowedOrigins;

    public CorsConfig(@Value("${app.cors.allowed-origins:}") String[] allowedOrigins) {
        this.allowedOrigins = Arrays.stream(allowedOrigins)
            .map(String::trim)
            .filter(origin -> !origin.isEmpty())
            .toList();
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (allowedOrigins.isEmpty()) {
            log.info("CORS : aucune origine externe autorisée (app.cors.allowed-origins vide)");
            return;
        }
        log.info("CORS : origines autorisées sur /api/** : {}", allowedOrigins);
        registry.addMapping("/api/**")
            .allowedOrigins(allowedOrigins.toArray(String[]::new))
            .allowedMethods("GET")
            .exposedHeaders(HttpHeaders.RETRY_AFTER);
    }
}
