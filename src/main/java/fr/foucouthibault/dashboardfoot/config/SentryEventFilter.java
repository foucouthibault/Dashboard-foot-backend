package fr.foucouthibault.dashboardfoot.config;

import fr.foucouthibault.dashboardfoot.exception.InvalidRequestException;
import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamErrorException;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.SentryOptions;
import io.sentry.protocol.Request;
import org.springframework.http.HttpStatus;

/**
 * Dernier contrôle avant l'envoi d'un événement à Sentry.
 *
 * <ul>
 *   <li><b>Tri :</b> les erreurs qui relèvent du fonctionnement normal ne sont pas envoyées :
 *   quota interne atteint ({@code 429} voulu), requête invalide ({@code 400}), statut relayé de
 *   football-data.org autre que {@code 429} ({@code 403} saison hors offre, {@code 404}
 *   compétition inconnue). Un {@code 429} de football-data.org est envoyé : le limiteur interne
 *   a laissé passer trop d'appels, ce qui est un bug et expose la clé à un blocage.</li>
 *   <li><b>Vie privée :</b> en plus de {@code sentry.send-default-pii=false}, la requête perd ses
 *   en-têtes, cookies et variables d'environnement (dont l'adresse du client), et l'événement
 *   son utilisateur. La clé football-data.org n'est jamais dans la requête entrante : elle n'est
 *   ajoutée qu'à l'appel sortant, que Sentry n'enregistre pas.</li>
 * </ul>
 */
public class SentryEventFilter implements SentryOptions.BeforeSendCallback {

    @Override
    public SentryEvent execute(SentryEvent event, Hint hint) {
        if (isExpected(event.getThrowable())) {
            return null;
        }
        Request request = event.getRequest();
        if (request != null) {
            request.setHeaders(null);
            request.setCookies(null);
            request.setEnvs(null);
            request.setData(null);
        }
        event.setUser(null);
        return event;
    }

    static boolean isExpected(Throwable throwable) {
        return throwable instanceof RateLimitExceededException
            || throwable instanceof InvalidRequestException
            || (throwable instanceof UpstreamErrorException e
                && !e.getStatus().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS));
    }
}
