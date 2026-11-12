package fr.foucouthibault.dashboardfoot.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Attribue un identifiant à chaque requête et le place dans le MDC sous la clé
 * {@code requestId} : toutes les lignes de log écrites pendant la requête le portent, ce qui
 * permet d'isoler une requête parmi d'autres dans les logs de Render.
 *
 * Un {@code X-Request-Id} entrant est repris s'il est court et sans caractère risqué (lettres,
 * chiffres, {@code .}, {@code _}, {@code -}, 64 au plus) ; sinon un UUID est généré, pour
 * qu'un client ne puisse ni injecter de texte dans les logs ni les gonfler. L'identifiant est
 * renvoyé dans l'en-tête de réponse {@code X-Request-Id} (exposé en CORS par {@link CorsConfig}).
 *
 * Placé en tête de chaîne pour couvrir aussi les réponses produites par les autres filtres
 * (refus CORS, par exemple).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        String requestId = requestIdFor(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    static String requestIdFor(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
