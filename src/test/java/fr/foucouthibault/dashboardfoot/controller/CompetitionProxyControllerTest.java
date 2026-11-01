package fr.foucouthibault.dashboardfoot.controller;

import fr.foucouthibault.dashboardfoot.exception.InvalidRequestException;
import fr.foucouthibault.dashboardfoot.model.CompetitionResponse;
import fr.foucouthibault.dashboardfoot.model.MatchesResponse;
import fr.foucouthibault.dashboardfoot.model.ScorersResponse;
import fr.foucouthibault.dashboardfoot.model.StandingsResponse;
import fr.foucouthibault.dashboardfoot.service.FootballDataProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Le contrôleur ne fait que valider l'ID et la saison, puis déléguer à FootballDataProxyService
 * (appels HTTP, cache, rate limiting) : ces tests couvrent uniquement le routage et la
 * validation. Une entrée invalide lève {@link InvalidRequestException}, transformée en 400 par
 * {@link ApiExceptionHandler} (vérifié dans CompetitionProxyHttpTest).
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

    private static void assertInvalid(String expectedMessage, Runnable call) {
        InvalidRequestException e = assertThrows(InvalidRequestException.class, call::run);
        assertEquals(expectedMessage, e.getMessage());
    }

    private static final String INVALID_ID = "Invalid competition ID format";
    private static final String INVALID_SEASON = "Invalid season format";

    // ==================== Validation de l'ID ====================

    @Test
    void testProxyCompetition_whenIdContainsSpecialChars_isRejected() {
        assertInvalid(INVALID_ID, () -> controller.proxyCompetition("FL1<script>", headers));
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyCompetition_whenIdContainsSlash_isRejected() {
        assertInvalid(INVALID_ID, () -> controller.proxyCompetition("FL1/../evil", headers));
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyStandings_whenInvalidId_isRejected() {
        assertInvalid(INVALID_ID, () -> controller.proxyStandings("PL<script>", null, headers));
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyMatches_whenInvalidId_isRejected() {
        assertInvalid(INVALID_ID, () -> controller.proxyMatches("BL1../", headers));
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyScorers_whenInvalidId_isRejected() {
        assertInvalid(INVALID_ID, () -> controller.proxyScorers("PL<script>", null, null, headers));
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyCompetition_whenIdWithSqlInjection_isRejected() {
        assertInvalid(INVALID_ID, () -> controller.proxyCompetition("FL1'; DROP TABLE users;--", headers));
    }

    @Test
    void testProxyCompetition_whenIdWithPathTraversal_isRejected() {
        assertInvalid(INVALID_ID, () -> controller.proxyCompetition("../../../etc/passwd", headers));
    }

    @Test
    void testProxyCompetition_whenIdIsNull_isRejected() {
        assertInvalid(INVALID_ID, () -> controller.proxyCompetition(null, headers));
        verifyNoInteractions(footballDataProxyService);
    }

    // ==================== Validation de la saison ====================

    @Test
    void testProxyStandings_whenInvalidSeason_isRejected() {
        assertInvalid(INVALID_SEASON, () -> controller.proxyStandings("FL1", "20x3", headers));
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyScorers_whenInvalidSeason_isRejected() {
        assertInvalid(INVALID_SEASON, () -> controller.proxyScorers("FL1", 5, "23", headers));
        verifyNoInteractions(footballDataProxyService);
    }

    @Test
    void testProxyStandings_whenSeasonIsEmpty_delegatesToService() {
        StandingsResponse serviceResponse = new StandingsResponse(List.of());
        when(footballDataProxyService.getStandings("FL1", "", headers)).thenReturn(serviceResponse);

        assertSame(serviceResponse, controller.proxyStandings("FL1", "", headers));
    }

    @Test
    void testProxyScorers_whenSeasonIsEmpty_delegatesToService() {
        ScorersResponse serviceResponse = new ScorersResponse(0, List.of());
        when(footballDataProxyService.getScorers("FL1", null, "", headers)).thenReturn(serviceResponse);

        assertSame(serviceResponse, controller.proxyScorers("FL1", null, "", headers));
    }

    @Test
    void testProxyStandings_whenIdAndSeasonAreInvalid_idErrorComesFirst() {
        assertInvalid(INVALID_ID, () -> controller.proxyStandings("bad id", "zzzz", headers));
    }

    // ==================== Délégation vers le service ====================

    @Test
    void testProxyCompetition_whenValidId_delegatesToService() {
        CompetitionResponse serviceResponse = new CompetitionResponse(2015L, "Ligue 1", "FL1", null);
        when(footballDataProxyService.getCompetition("FL1", headers)).thenReturn(serviceResponse);

        assertSame(serviceResponse, controller.proxyCompetition("FL1", headers));
    }

    @Test
    void testProxyStandings_whenValidId_delegatesWithSeason() {
        StandingsResponse serviceResponse = new StandingsResponse(List.of());
        when(footballDataProxyService.getStandings("FL1", "2023", headers)).thenReturn(serviceResponse);

        assertSame(serviceResponse, controller.proxyStandings("FL1", "2023", headers));
        verify(footballDataProxyService).getStandings("FL1", "2023", headers);
    }

    @Test
    void testProxyMatches_whenValidId_delegatesToService() {
        MatchesResponse serviceResponse = new MatchesResponse(List.of());
        when(footballDataProxyService.getMatches("BL1", headers)).thenReturn(serviceResponse);

        assertSame(serviceResponse, controller.proxyMatches("BL1", headers));
    }

    @Test
    void testProxyScorers_whenValidId_delegatesWithLimitAndSeason() {
        ScorersResponse serviceResponse = new ScorersResponse(0, List.of());
        when(footballDataProxyService.getScorers("FL1", 5, "2023", headers)).thenReturn(serviceResponse);

        assertSame(serviceResponse, controller.proxyScorers("FL1", 5, "2023", headers));
        verify(footballDataProxyService).getScorers("FL1", 5, "2023", headers);
    }

    @Test
    void testProxyScorers_whenNoLimitOrSeason_delegatesWithNulls() {
        ScorersResponse serviceResponse = new ScorersResponse(0, List.of());
        when(footballDataProxyService.getScorers(eq("PL"), isNull(), isNull(), any())).thenReturn(serviceResponse);

        assertSame(serviceResponse, controller.proxyScorers("PL", null, null, headers));
    }
}
