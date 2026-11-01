package fr.foucouthibault.dashboardfoot.controller;

import fr.foucouthibault.dashboardfoot.exception.InvalidRequestException;
import fr.foucouthibault.dashboardfoot.model.CompetitionResponse;
import fr.foucouthibault.dashboardfoot.model.MatchesResponse;
import fr.foucouthibault.dashboardfoot.model.ScorersResponse;
import fr.foucouthibault.dashboardfoot.model.StandingsResponse;
import fr.foucouthibault.dashboardfoot.service.FootballDataProxyService;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/competitions")
public class CompetitionProxyController {

    private static final String VALID_ID_PATTERN = "^[a-zA-Z0-9_-]+$";
    private static final String VALID_SEASON_PATTERN = "^\\d{4}$";

    private final FootballDataProxyService footballDataProxyService;

    public CompetitionProxyController(FootballDataProxyService footballDataProxyService) {
        this.footballDataProxyService = footballDataProxyService;
    }

    private static void validateId(String id) {
        if (id == null || !id.matches(VALID_ID_PATTERN)) {
            throw new InvalidRequestException("Format d'identifiant de compétition invalide");
        }
    }

    private static void validateSeason(String season) {
        if (season != null && !season.isEmpty() && !season.matches(VALID_SEASON_PATTERN)) {
            throw new InvalidRequestException("Format de saison invalide");
        }
    }

    @GetMapping("/{id}")
    public CompetitionResponse proxyCompetition(@PathVariable String id,
                                                @RequestHeader(required = false) HttpHeaders headers) {
        validateId(id);
        return footballDataProxyService.getCompetition(id, headers);
    }

    @GetMapping("/{id}/standings")
    public StandingsResponse proxyStandings(@PathVariable String id,
                                            @RequestParam(required = false) String season,
                                            @RequestHeader(required = false) HttpHeaders headers) {
        validateId(id);
        validateSeason(season);
        return footballDataProxyService.getStandings(id, season, headers);
    }

    @GetMapping("/{id}/matches")
    public MatchesResponse proxyMatches(@PathVariable String id,
                                        @RequestHeader(required = false) HttpHeaders headers) {
        validateId(id);
        return footballDataProxyService.getMatches(id, headers);
    }

    @GetMapping("/{id}/scorers")
    public ScorersResponse proxyScorers(@PathVariable String id,
                                        @RequestParam(required = false) Integer limit,
                                        @RequestParam(required = false) String season,
                                        @RequestHeader(required = false) HttpHeaders headers) {
        validateId(id);
        validateSeason(season);
        return footballDataProxyService.getScorers(id, limit, season, headers);
    }
}
