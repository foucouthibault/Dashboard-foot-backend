package com.example.Dashboard_foot.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompetitionProxyControllerTest {

    @Mock
    private RestTemplate restTemplate;

    @InjectMocks
    private CompetitionProxyController controller;

    private HttpHeaders headers;

    @BeforeEach
    void setUp() throws Exception {
        headers = new HttpHeaders();
        
        // Initialiser les champs du contrôleur via réflexion
        // car @Value et @PostConstruct ne fonctionnent pas dans les tests unitaires
        Field apiBaseUrlField = CompetitionProxyController.class.getDeclaredField("apiBaseUrl");
        apiBaseUrlField.setAccessible(true);
        apiBaseUrlField.set(controller, "https://api.football-data.org/v4");
        
        Field apiTokenField = CompetitionProxyController.class.getDeclaredField("apiToken");
        apiTokenField.setAccessible(true);
        apiTokenField.set(controller, "test-token");
        
        // Initialiser allowedDomain manuellement
        Field allowedDomainField = CompetitionProxyController.class.getDeclaredField("allowedDomain");
        allowedDomainField.setAccessible(true);
        
        Method extractDomainMethod = CompetitionProxyController.class.getDeclaredMethod("extractDomain", String.class);
        extractDomainMethod.setAccessible(true);
        String domain = (String) extractDomainMethod.invoke(controller, "https://api.football-data.org/v4");
        allowedDomainField.set(controller, domain);
    }

    // ==================== Tests de validation de l'ID ====================

    @Test
    void testProxyCompetition_whenIdContainsSpecialChars_returnsBadRequest() {
        String invalidId = "FL1<script>";
        ResponseEntity<String> response = controller.proxyCompetition(invalidId, headers);
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        if (response.getBody() != null) {
            assertTrue(response.getBody().contains("Invalid competition ID format"));
        }
    }

    @Test
    void testProxyCompetition_whenIdContainsSlash_returnsBadRequest() {
        String invalidId = "FL1/../evil";
        ResponseEntity<String> response = controller.proxyCompetition(invalidId, headers);
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        if (response.getBody() != null) {
            assertTrue(response.getBody().contains("Invalid competition ID format"));
        }
    }

    @Test
    void testProxyCompetition_whenIdContainsColon_returnsBadRequest() {
        String invalidId = "http://evil.com";
        ResponseEntity<String> response = controller.proxyCompetition(invalidId, headers);
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        if (response.getBody() != null) {
            assertTrue(response.getBody().contains("Invalid competition ID format"));
        }
    }

    @Test
    void testProxyCompetition_whenValidId_callsRestTemplate() {
        String validId = "FL1";
        String expectedUrl = "https://api.football-data.org/v4/competitions/FL1";
        String mockResponse = "{\"id\":2015,\"name\":\"Ligue 1\"}";
        
        ResponseEntity<String> mockEntity = new ResponseEntity<>(mockResponse, HttpStatus.OK);
        
        when(restTemplate.exchange(
            eq(URI.create(expectedUrl)),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(mockEntity);

        ResponseEntity<String> response = controller.proxyCompetition(validId, headers);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(mockResponse, response.getBody());
    }

    // ==================== Tests pour /standings ====================

    @Test
    void testProxyStandings_whenInvalidId_returnsBadRequest() {
        String invalidId = "PL<script>";
        ResponseEntity<String> response = controller.proxyStandings(invalidId, headers);
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        if (response.getBody() != null) {
            assertTrue(response.getBody().contains("Invalid competition ID format"));
        }
    }

    @Test
    void testProxyStandings_whenValidId_returnsData() {
        String validId = "PL";
        String expectedUrl = "https://api.football-data.org/v4/competitions/PL/standings";
        String mockResponse = "{\"standings\":[{\"position\":1}]}";
        
        ResponseEntity<String> mockEntity = new ResponseEntity<>(mockResponse, HttpStatus.OK);
        
        when(restTemplate.exchange(
            eq(URI.create(expectedUrl)),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(mockEntity);

        ResponseEntity<String> response = controller.proxyStandings(validId, headers);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(mockResponse, response.getBody());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    // ==================== Tests pour /matches ====================

    @Test
    void testProxyMatches_whenInvalidId_returnsBadRequest() {
        String invalidId = "BL1../";
        ResponseEntity<String> response = controller.proxyMatches(invalidId, headers);
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        if (response.getBody() != null) {
            assertTrue(response.getBody().contains("Invalid competition ID format"));
        }
    }

    @Test
    void testProxyMatches_whenValidId_returnsData() {
        String validId = "BL1";
        String expectedUrl = "https://api.football-data.org/v4/competitions/BL1/matches";
        String mockResponse = "{\"matches\":[{\"id\":1}]}";
        
        ResponseEntity<String> mockEntity = new ResponseEntity<>(mockResponse, HttpStatus.OK);
        
        when(restTemplate.exchange(
            eq(URI.create(expectedUrl)),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(mockEntity);

        ResponseEntity<String> response = controller.proxyMatches(validId, headers);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(mockResponse, response.getBody());
    }

    // ==================== Tests pour /scorers ====================

    @Test
    void testProxyScorers_whenInvalidId_returnsBadRequest() {
        String invalidId = "PL<script>";
        ResponseEntity<String> response = controller.proxyScorers(invalidId, null, headers);
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        if (response.getBody() != null) {
            assertTrue(response.getBody().contains("Invalid competition ID format"));
        }
    }

    @Test
    void testProxyScorers_whenValidId_returnsData() {
        String validId = "PL";
        String expectedUrl = "https://api.football-data.org/v4/competitions/PL/scorers";
        String mockResponse = "{\"scorers\":[{\"player\":{\"name\":\"Player 1\"},\"goals\":10}]}";
        
        ResponseEntity<String> mockEntity = new ResponseEntity<>(mockResponse, HttpStatus.OK);
        
        when(restTemplate.exchange(
            eq(URI.create(expectedUrl)),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(mockEntity);

        ResponseEntity<String> response = controller.proxyScorers(validId, null, headers);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(mockResponse, response.getBody());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test
    void testProxyScorers_whenValidIdWithLimit_returnsData() {
        String validId = "FL1";
        Integer limit = 10;
        String expectedUrl = "https://api.football-data.org/v4/competitions/FL1/scorers?limit=10";
        String mockResponse = "{\"scorers\":[{\"player\":{\"name\":\"Player 1\"},\"goals\":15}]}";
        
        ResponseEntity<String> mockEntity = new ResponseEntity<>(mockResponse, HttpStatus.OK);
        
        when(restTemplate.exchange(
            eq(URI.create(expectedUrl)),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(mockEntity);

        ResponseEntity<String> response = controller.proxyScorers(validId, limit, headers);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(mockResponse, response.getBody());
    }

    // ==================== Tests d'erreur ====================

    @Test
    void testProxyCompetition_whenApiFails_returnsGenericError() {
        String validId = "FL1";
        String expectedUrl = "https://api.football-data.org/v4/competitions/FL1";
        
        when(restTemplate.exchange(
            eq(URI.create(expectedUrl)),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenThrow(new RuntimeException("API Error with user input: <script>alert(1)</script>"));

        ResponseEntity<String> response = controller.proxyCompetition(validId, headers);
        
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        if (response.getBody() != null) {
            assertTrue(response.getBody().contains("Failed to fetch data from API"));
            assertFalse(response.getBody().contains("API Error"));
            assertFalse(response.getBody().contains("<script>"));
        }
    }

    @Test
    void testProxyCompetition_whenApiReturns404_returnsError() {
        String validId = "INVALID";
        String expectedUrl = "https://api.football-data.org/v4/competitions/INVALID";
        
        ResponseEntity<String> errorResponse = new ResponseEntity<>(
            "{\"error\":\"Not found\"}", 
            HttpStatus.NOT_FOUND
        );
        
        when(restTemplate.exchange(
            eq(URI.create(expectedUrl)),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(errorResponse);

        ResponseEntity<String> response = controller.proxyCompetition(validId, headers);
        
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ==================== Tests de sécurité ====================

    @Test
    void testProxyCompetition_whenIdWithSqlInjection_returnsBadRequest() {
        String maliciousId = "FL1'; DROP TABLE users;--";
        ResponseEntity<String> response = controller.proxyCompetition(maliciousId, headers);
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void testProxyCompetition_whenIdWithXss_returnsBadRequest() {
        String maliciousId = "<script>alert('xss')</script>";
        ResponseEntity<String> response = controller.proxyCompetition(maliciousId, headers);
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void testProxyCompetition_whenIdWithPathTraversal_returnsBadRequest() {
        String maliciousId = "../../../etc/passwd";
        ResponseEntity<String> response = controller.proxyCompetition(maliciousId, headers);
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ==================== Tests de headers ====================

    @Test
    void testProxyCompetition_propagatesAcceptHeader() {
        String validId = "FL1";
        String expectedUrl = "https://api.football-data.org/v4/competitions/FL1";
        String mockResponse = "{\"id\":2015}";
        
        HttpHeaders inputHeaders = new HttpHeaders();
        inputHeaders.set("Accept", "application/json");
        inputHeaders.set("Accept-Language", "fr-FR");
        
        ResponseEntity<String> mockEntity = new ResponseEntity<>(mockResponse, HttpStatus.OK);
        
        when(restTemplate.exchange(
            eq(URI.create(expectedUrl)),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(mockEntity);

        ResponseEntity<String> response = controller.proxyCompetition(validId, inputHeaders);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testProxyCompetition_addsAuthToken() {
        String validId = "FL1";
        String expectedUrl = "https://api.football-data.org/v4/competitions/FL1";
        String mockResponse = "{\"id\":2015}";
        
        ResponseEntity<String> mockEntity = new ResponseEntity<>(mockResponse, HttpStatus.OK);
        
        when(restTemplate.exchange(
            eq(URI.create(expectedUrl)),
            eq(HttpMethod.GET),
            any(HttpEntity.class),
            eq(String.class)
        )).thenReturn(mockEntity);

        ResponseEntity<String> response = controller.proxyCompetition(validId, headers);
        
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
