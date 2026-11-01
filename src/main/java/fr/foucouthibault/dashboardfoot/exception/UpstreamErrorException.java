package fr.foucouthibault.dashboardfoot.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/**
 * football-data.org a répondu par un statut d'erreur (saison hors plan, quota atteint...) :
 * statut, type de contenu et corps sont renvoyés tels quels au client.
 */
public class UpstreamErrorException extends RuntimeException {

    private final HttpStatusCode status;
    private final MediaType contentType;
    private final String body;

    public UpstreamErrorException(HttpStatusCode status, MediaType contentType, String body) {
        super("football-data.org a répondu " + status);
        this.status = status;
        this.contentType = contentType;
        this.body = body;
    }

    public HttpStatusCode getStatus() {
        return status;
    }

    public MediaType getContentType() {
        return contentType;
    }

    public String getBody() {
        return body;
    }
}
