package fr.foucouthibault.dashboardfoot.exception;

import org.springframework.http.HttpStatusCode;

/**
 * football-data.org a répondu par un statut d'erreur (saison hors abonnement, quota atteint...) :
 * ce statut est renvoyé au client. Le corps de la réponse amont n'est que journalisé par le
 * service, il n'est pas transporté jusqu'au client.
 */
public class UpstreamErrorException extends RuntimeException {

    private final HttpStatusCode status;

    public UpstreamErrorException(HttpStatusCode status) {
        super("football-data.org a répondu " + status);
        this.status = status;
    }

    public HttpStatusCode getStatus() {
        return status;
    }
}
