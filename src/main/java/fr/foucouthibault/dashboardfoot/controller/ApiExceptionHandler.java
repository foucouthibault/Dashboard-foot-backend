package fr.foucouthibault.dashboardfoot.controller;

import fr.foucouthibault.dashboardfoot.exception.InvalidRequestException;
import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamErrorException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUnavailableException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUrlRejectedException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Traduit les exceptions du contrôleur et du service en réponses HTTP, avec les mêmes corps
 * {@code {"error": "..."}} qu'avant les réponses typées (#34). Le passage à ProblemDetail est
 * l'objet de #35.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    /** Corps des erreurs produites par ce service. */
    public record ApiError(String error) {
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ApiError> invalidRequest(InvalidRequestException e) {
        return error(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(UpstreamUrlRejectedException.class)
    public ResponseEntity<ApiError> upstreamUrlRejected() {
        return error(HttpStatus.BAD_REQUEST, "Invalid upstream URL");
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> rateLimitExceeded(RateLimitExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.getRetryAfterSeconds()))
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ApiError("Trop de requêtes vers l'API football-data.org, réessayez dans quelques secondes"));
    }

    @ExceptionHandler(UpstreamUnavailableException.class)
    public ResponseEntity<ApiError> upstreamUnavailable() {
        return error(HttpStatus.BAD_GATEWAY, "Failed to fetch data from API");
    }

    /** Erreur de football-data.org relayée telle quelle : son corps n'est pas forcément du JSON. */
    @ExceptionHandler(UpstreamErrorException.class)
    public ResponseEntity<String> upstreamError(UpstreamErrorException e) {
        return ResponseEntity.status(e.getStatus())
            .contentType(e.getContentType())
            .body(e.getBody());
    }

    private static ResponseEntity<ApiError> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(new ApiError(message));
    }
}
