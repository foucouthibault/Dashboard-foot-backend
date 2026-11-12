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
 * Les en-têtes de requête acceptés sont limités à ceux du frontend : {@code Accept},
 * {@code Accept-Language}, et les en-têtes de suivi que le SDK Sentry du navigateur ajoute
 * ({@code sentry-trace}, {@code baggage}) pour relier une erreur du frontend à la requête du backend.
 *
 * {@code Retry-After} (réponse {@code 429}) et {@code X-Request-Id} sont exposés : sans cela, le
 * navigateur les masque au frontend, qui ne pourrait ni annoncer le délai d'attente ni citer
 * l'identifiant de la requête (voir {@link RequestIdFilter}) pour la retrouver dans les logs.
 *
 * Avec une règle, Spring répond {@code 403} à une requête dont l'origine n'est pas dans la
 * liste, avant d'atteindre le contrôleur : elle ne consomme pas le quota de football-data.org.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CorsConfig.class);

    /** En-têtes de propagation de trace du SDK Sentry (frontend). */
    static final String SENTRY_TRACE = "sentry-trace";
    static final String BAGGAGE = "baggage";

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
            .allowedHeaders(HttpHeaders.ACCEPT, HttpHeaders.ACCEPT_LANGUAGE, SENTRY_TRACE, BAGGAGE)
            .exposedHeaders(HttpHeaders.RETRY_AFTER, RequestIdFilter.HEADER);
    }
}
