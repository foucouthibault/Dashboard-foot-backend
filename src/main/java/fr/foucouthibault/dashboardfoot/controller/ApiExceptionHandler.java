package fr.foucouthibault.dashboardfoot.controller;

import fr.foucouthibault.dashboardfoot.exception.InvalidRequestException;
import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamErrorException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUnavailableException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUrlRejectedException;
import io.sentry.Sentry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Traduit les exceptions du contrôleur et du service en ProblemDetail (RFC 9457), toujours en
 * {@code application/problem+json}. Le {@code detail} est un texte fixe : aucun message
 * d'exception ni corps de réponse de football-data.org n'est renvoyé au client (ils sont
 * journalisés par le service). Les erreurs levées par Spring MVC (paramètre mal typé, route
 * inconnue...) sont traitées par {@link ResponseEntityExceptionHandler}, au même format.
 *
 * Sentry ne voit que les exceptions qu'aucun gestionnaire n'a traitées : celles gérées ici et
 * qui signalent une panne ({@code 502}, {@code 429} de football-data.org) lui sont envoyées
 * explicitement. {@code Sentry.captureException} ne fait rien quand le SDK est désactivé.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(InvalidRequestException e) {
        return problem(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(UpstreamUrlRejectedException.class)
    public ResponseEntity<ProblemDetail> upstreamUrlRejected() {
        return problem(HttpStatus.BAD_REQUEST, "URL amont invalide");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> rateLimitExceeded(RateLimitExceededException e) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()));
        return problem(HttpStatus.TOO_MANY_REQUESTS,
            "Trop de requêtes vers l'API football-data.org, réessayez dans quelques secondes", headers);
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ProblemDetail> upstreamUnavailable(UpstreamUnavailableException e) {
        Sentry.captureException(e);
        return problem(HttpStatus.BAD_GATEWAY, "Échec de l'appel à l'API football-data.org");
    }

    /** Le statut de football-data.org est conservé (403 saison hors abonnement, 404, 429...). */
    @ExceptionHandler(UpstreamErrorException.class)
    public ResponseEntity<ProblemDetail> upstreamError(UpstreamErrorException e) {
        // Seul le 429 est une anomalie de notre côté ; SentryEventFilter écarte les autres statuts.
        if (e.getStatus().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)) {
            Sentry.captureException(e);
        }
        return problem(e.getStatus(), "L'API football-data.org a répondu par une erreur");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String detail) {
        return problem(status, detail, HttpHeaders.EMPTY);
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatusCode status, String detail, HttpHeaders headers) {
        return ResponseEntity.status(status)
            .headers(headers)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
