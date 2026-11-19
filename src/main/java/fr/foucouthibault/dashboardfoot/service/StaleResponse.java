package fr.foucouthibault.dashboardfoot.service;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.time.Duration;
import java.util.Optional;

/**
 * Signale qu'une requête a été servie depuis la copie de secours, et avec quel âge, sans changer
 * le type des réponses du service : l'information est posée en attribut de la requête en cours,
 * puis {@code StaleResponseAgeAdvice} la traduit en en-tête {@code Age} (RFC 9111). Hors requête
 * HTTP (tâche planifiée, test unitaire), rien n'est posé.
 */
public final class StaleResponse {

    static final String AGE_ATTRIBUTE = StaleResponse.class.getName() + ".age";

    private StaleResponse() {
    }

    static void markCurrentRequest(Duration age) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            attributes.setAttribute(AGE_ATTRIBUTE, age, RequestAttributes.SCOPE_REQUEST);
        }
    }

    /** Âge de la copie servie pour cette requête ; vide si la réponse est fraîche. */
    public static Optional<Duration> ageOf(HttpServletRequest request) {
        return request.getAttribute(AGE_ATTRIBUTE) instanceof Duration age ? Optional.of(age) : Optional.empty();
    }
}
