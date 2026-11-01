package fr.foucouthibault.dashboardfoot.exception;

/** Paramètre client invalide (id, saison) : répondu en 400. */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
