package fr.foucouthibault.dashboardfoot.controller;

import fr.foucouthibault.dashboardfoot.exception.InvalidRequestException;
import fr.foucouthibault.dashboardfoot.model.CompetitionResponse;
import fr.foucouthibault.dashboardfoot.model.MatchesResponse;
import fr.foucouthibault.dashboardfoot.model.ScorersResponse;
import fr.foucouthibault.dashboardfoot.model.StandingsResponse;
import fr.foucouthibault.dashboardfoot.service.FootballDataProxyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.*;

/**
 * Les réponses d'erreur, communes aux 4 routes, sont décrites ici pour la documentation
 * OpenAPI ; elles sont produites par {@link ApiExceptionHandler}.
 */
@RestController
@RequestMapping("/api/competitions")
@Tag(name = "Compétitions", description = "Données de football-data.org, mises en cache")
@ApiResponse(responseCode = "400", description = "Paramètre invalide (identifiant, saison, limit mal typé)",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "403", description = "Ressource hors de l'offre football-data.org, par exemple une saison trop ancienne (statut relayé)",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "404", description = "Compétition inconnue de football-data.org (statut relayé)",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "429", description = "Quota d'appels à football-data.org atteint (10 par minute, pour tous les visiteurs)",
    headers = @Header(name = "Retry-After", description = "Secondes à attendre avant de réessayer", schema = @Schema(type = "integer")),
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "502", description = "football-data.org injoignable, ou réponse vide ou illisible",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
@ApiResponse(responseCode = "default", description = "Autre erreur de football-data.org : son statut est relayé, pas son corps",
    content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
public class CompetitionProxyController {

    private static final String VALID_ID_PATTERN = "^[a-zA-Z0-9_-]+$";
    private static final String VALID_SEASON_PATTERN = "^\\d{4}$";

    private static final String ID_DESCRIPTION = "Code (FL1, PL…) ou identifiant numérique de la compétition";
    private static final String SEASON_DESCRIPTION = "Année de début de la saison ; saison en cours si absent";
    private static final String AGE_DESCRIPTION = "Présent seulement quand football-data.org est indisponible ou le quota "
        + "atteint : la réponse vient alors de la copie de secours, et l'en-tête donne son âge en secondes";
    private static final String LIVE_CACHE = "60 s pendant un match de la compétition, sinon jusqu'au prochain coup d'envoi "
        + "(entre 60 s et 1 h)";
    private static final String SEASON_CACHE = "Cache, saison en cours : " + LIVE_CACHE
        + " ; 5 min si les matchs de la compétition n'ont pas encore été demandés. Saison terminée : 30 jours.";

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
    @ApiResponse(responseCode = "200", description = "OK",
        headers = @Header(name = "Age", description = AGE_DESCRIPTION, schema = @Schema(type = "integer")))
    @Operation(summary = "Compétition", description = "Nom, code et logo de la compétition. Cache : 24 h.")
    public CompetitionResponse proxyCompetition(
            @Parameter(description = ID_DESCRIPTION, example = "FL1", schema = @Schema(pattern = VALID_ID_PATTERN))
            @PathVariable String id,
            @Parameter(hidden = true) @RequestHeader(required = false) HttpHeaders headers) {
        validateId(id);
        return footballDataProxyService.getCompetition(id, headers);
    }

    @GetMapping("/{id}/standings")
    @ApiResponse(responseCode = "200", description = "OK",
        headers = @Header(name = "Age", description = AGE_DESCRIPTION, schema = @Schema(type = "integer")))
    @Operation(summary = "Classement",
        description = "Classements général, domicile et extérieur (par poule le cas échéant). " + SEASON_CACHE)
    public StandingsResponse proxyStandings(
            @Parameter(description = ID_DESCRIPTION, example = "FL1", schema = @Schema(pattern = VALID_ID_PATTERN))
            @PathVariable String id,
            @Parameter(description = SEASON_DESCRIPTION, example = "2025", schema = @Schema(pattern = VALID_SEASON_PATTERN))
            @RequestParam(required = false) String season,
            @Parameter(hidden = true) @RequestHeader(required = false) HttpHeaders headers) {
        validateId(id);
        validateSeason(season);
        return footballDataProxyService.getStandings(id, season, headers);
    }

    @GetMapping("/{id}/matches")
    @ApiResponse(responseCode = "200", description = "OK",
        headers = @Header(name = "Age", description = AGE_DESCRIPTION, schema = @Schema(type = "integer")))
    @Operation(summary = "Matchs", description = "Matchs de la saison en cours. Cache : " + LIVE_CACHE + ".")
    public MatchesResponse proxyMatches(
            @Parameter(description = ID_DESCRIPTION, example = "FL1", schema = @Schema(pattern = VALID_ID_PATTERN))
            @PathVariable String id,
            @Parameter(hidden = true) @RequestHeader(required = false) HttpHeaders headers) {
        validateId(id);
        return footballDataProxyService.getMatches(id, headers);
    }

    @GetMapping("/{id}/scorers")
    @ApiResponse(responseCode = "200", description = "OK",
        headers = @Header(name = "Age", description = AGE_DESCRIPTION, schema = @Schema(type = "integer")))
    @Operation(summary = "Buteurs",
        description = "Meilleurs buteurs. Les 50 premiers sont mis en cache puis découpés selon limit : "
            + "changer de limit ne consomme pas de quota. " + SEASON_CACHE)
    public ScorersResponse proxyScorers(
            @Parameter(description = ID_DESCRIPTION, example = "FL1", schema = @Schema(pattern = VALID_ID_PATTERN))
            @PathVariable String id,
            @Parameter(description = "Nombre de buteurs renvoyés (50 au plus ; tous si absent)", example = "10")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = SEASON_DESCRIPTION, example = "2025", schema = @Schema(pattern = VALID_SEASON_PATTERN))
            @RequestParam(required = false) String season,
            @Parameter(hidden = true) @RequestHeader(required = false) HttpHeaders headers) {
        validateId(id);
        validateSeason(season);
        return footballDataProxyService.getScorers(id, limit, season, headers);
    }
}
