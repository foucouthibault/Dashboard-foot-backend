package com.example.Dashboard_foot.controller;

import com.example.Dashboard_foot.service.FootballDataProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le contrôleur ne fait plus que valider l'ID et déléguer à FootballDataProxyService
 * (appels HTTP, cache, rate limiting) : ces tests couvrent uniquement le routage et
 * la validation. Le comportement du proxy est testé dans FootballDataProxyServiceTest.
 */
@ExtendWith(MockitoExtension.class)
class CompetitionProxyControllerTest {

    @Mock
    private FootballDataProxyService footballDataProxyService;

    private CompetitionProxyController controller;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        controller = new CompetitionProxyController(footballDataProxyService);
        headers = new HttpHeaders();
    }

    // ==================== Validation de l'ID ====================

    @Test
    void testProxyCompetition_whenIdContainsSpecialChars_returnsBadRequest() {
        ResponseEntity<String> response = controller.proxyCompetition("FL1<script>", headers);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("Invalid competition ID format"));
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyCompetition_whenIdContainsSlash_returnsBadRequest() {
        ResponseEntity<String> response = controller.proxyCompetition("FL1/../evil", headers);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyStandings_whenInvalidId_returnsBadRequest() {
        ResponseEntity<String> response = controller.proxyStandings("PL<script>", null, headers);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyMatches_whenInvalidId_returnsBadRequest() {
        ResponseEntity<String> response = controller.proxyMatches("BL1../", headers);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyScorers_whenInvalidId_returnsBadRequest() {
        ResponseEntity<String> response = controller.proxyScorers("PL<script>", null, null, headers);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyCompetition_whenIdWithSqlInjection_returnsBadRequest() {
        ResponseEntity<String> response = controller.proxyCompetition("FL1'; DROP TABLE users;--", headers);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void testProxyCompetition_whenIdWithPathTraversal_returnsBadRequest() {
        ResponseEntity<String> response = controller.proxyCompetition("../../../etc/passwd", headers);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    // ==================== Délégation vers le service ====================

    @Test
    void testProxyCompetition_whenValidId_delegatesToService() {
        String mockResponse = "{\"id\":2015,\"name\":\"Ligue 1\"}";
        ResponseEntity<String> serviceResponse = new ResponseEntity<>(mockResponse, HttpStatus.OK);
        when(footballDataProxyService.getCompetition("FL1", headers)).thenReturn(serviceResponse);

        ResponseEntity<String> response = controller.proxyCompetition("FL1", headers);

        assertSame(serviceResponse, response);
    }

    @Test
    void testProxyStandings_whenValidId_delegatesWithSeason() {
        ResponseEntity<String> serviceResponse = new ResponseEntity<>("{\"standings\":[]}", HttpStatus.OK);
        when(footballDataProxyService.getStandings("FL1", "2023", headers)).thenReturn(serviceResponse);

        ResponseEntity<String> response = controller.proxyStandings("FL1", "2023", headers);

        assertSame(serviceResponse, response);
        verify(footballDataProxyService).getStandings("FL1", "2023", headers);
    }

    @Test
    void testProxyMatches_whenValidId_delegatesToService() {
        ResponseEntity<String> serviceResponse = new ResponseEntity<>("{\"matches\":[]}", HttpStatus.OK);
        when(footballDataProxyService.getMatches("BL1", headers)).thenReturn(serviceResponse);

        ResponseEntity<String> response = controller.proxyMatches("BL1", headers);

        assertSame(serviceResponse, response);
    }

    @Test
    void testProxyScorers_whenValidId_delegatesWithLimitAndSeason() {
        ResponseEntity<String> serviceResponse = new ResponseEntity<>("{\"scorers\":[]}", HttpStatus.OK);
        when(footballDataProxyService.getScorers("FL1", 5, "2023", headers)).thenReturn(serviceResponse);

        ResponseEntity<String> response = controller.proxyScorers("FL1", 5, "2023", headers);

        assertSame(serviceResponse, response);
        verify(footballDataProxyService).getScorers("FL1", 5, "2023", headers);
    }

    @Test
    void testProxyScorers_whenNoLimitOrSeason_delegatesWithNulls() {
        ResponseEntity<String> serviceResponse = new ResponseEntity<>("{\"scorers\":[]}", HttpStatus.OK);
        when(footballDataProxyService.getScorers(eq("PL"), isNull(), isNull(), any())).thenReturn(serviceResponse);

        ResponseEntity<String> response = controller.proxyScorers("PL", null, null, headers);

        assertSame(serviceResponse, response);
    }
}
