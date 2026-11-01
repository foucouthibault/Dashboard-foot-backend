package fr.foucouthibault.dashboardfoot.model;

import java.util.List;

/** Réponse de {@code GET /api/competitions/{id}/standings} (type frontend {@code StandingsResponse}). */
public record StandingsResponse(List<StandingGroup> standings) {

    public StandingsResponse {
        standings = Lists.immutable(standings);
    }

    /** Un tableau de classement : {@code type} vaut TOTAL, HOME ou AWAY ; {@code group} est null hors poules. */
    public record StandingGroup(String stage, String type, String group, List<StandingRow> table) {

        public StandingGroup {
            table = Lists.immutable(table);
        }
    }

    public record StandingRow(Integer position, Team team, Integer points, Integer playedGames,
                              Integer won, Integer draw, Integer lost,
                              Integer goalsFor, Integer goalsAgainst, Integer goalDifference) {
    }
}
