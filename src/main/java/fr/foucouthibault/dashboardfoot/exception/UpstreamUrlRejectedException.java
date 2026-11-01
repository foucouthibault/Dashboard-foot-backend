package fr.foucouthibault.dashboardfoot.exception;

/** URL sortante refusée par le contrôle SSRF : répondu en 400. */
public class UpstreamUrlRejectedException extends RuntimeException {

    public UpstreamUrlRejectedException() {
        super("URL sortante refusée");
    }
}
