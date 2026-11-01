package fr.foucouthibault.dashboardfoot.exception;

/**
 * Appel à football-data.org impossible (panne réseau, URL mal configurée) ou réponse
 * inexploitable : répondu en 502. Le message ne reprend jamais celui de la cause, pour ne
 * rien exposer de l'infrastructure.
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(Throwable cause) {
        super("Échec de l'appel à football-data.org", cause);
    }
}
