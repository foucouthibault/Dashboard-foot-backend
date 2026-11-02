package fr.foucouthibault.dashboardfoot.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cas non atteignables par CompetitionProxyHttpTest sans mal configurer l'URL de l'API :
 * les autres traductions d'exceptions sont vérifiées de bout en bout par ce test HTTP.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void upstreamUrlRejected_gives400ProblemDetailWithoutDetails() {
        ResponseEntity<ProblemDetail> response = handler.upstreamUrlRejected();

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, response.getHeaders().getContentType());
        ProblemDetail body = response.getBody();
        assertEquals(400, body.getStatus());
        assertEquals("Bad Request", body.getTitle());
        assertEquals("URL amont invalide", body.getDetail());
    }
}
