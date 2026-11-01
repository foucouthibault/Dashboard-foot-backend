package fr.foucouthibault.dashboardfoot.exception;

/** Quota interne d'appels à football-data.org atteint : répondu en 429 avec {@code Retry-After}. */
public class RateLimitExceededException extends RuntimeException {

    private final long retryAfterSeconds;

    public RateLimitExceededException(long retryAfterSeconds) {
        super("Quota d'appels à football-data.org atteint");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
