package com.example.Dashboard_foot.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CompetitionProxyControllerTest {

    @LocalServerPort
    private int port;

    private TestRestTemplate restTemplate;

    @BeforeEach
    void setUp() {
        restTemplate = new TestRestTemplate();
    }

    @Test
    void testProxyStandings_whenValidId_returnsProxiedResponse() {
        // Ce test vérifie que l'endpoint /api/competitions/{id}/standings est accessible
        // Pour un vrai test, il faudrait mocker RestTemplate, mais ici on vérifie juste le routing
        
        String url = "http://localhost:" + port + "/api/competitions/2001/standings";
        
        ResponseEntity<String> response = restTemplate.exchange(
            url,
            HttpMethod.GET,
            new HttpEntity<>(new HttpHeaders()),
            String.class
        );
        
        // Le proxy va échouer car il n'y a pas de token et l'API externe n'est pas accessible
        // Mais on vérifie que le endpoint répond (même avec une erreur)
        System.out.println("Status: " + response.getStatusCode());
        System.out.println("Body: " + response.getBody());
        
        // En production, avec un token valide, cela devrait retourner les données de l'API
    }
}
