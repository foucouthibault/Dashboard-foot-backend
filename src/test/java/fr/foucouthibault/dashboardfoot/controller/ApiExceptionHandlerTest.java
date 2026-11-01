package fr.foucouthibault.dashboardfoot.controller;

import fr.foucouthibault.dashboardfoot.controller.ApiExceptionHandler.ApiError;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cas non atteignables par CompetitionProxyHttpTest sans mal configurer l'URL de l'API :
 * les autres traductions d'exceptions sont vérifiées de bout en bout par ce test HTTP.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void upstreamUrlRejected_gives400InJsonWithoutDetails() {
        ResponseEntity<ApiError> response = handler.upstreamUrlRejected();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertEquals(new ApiError("Invalid upstream URL"), response.getBody());
    }
}
