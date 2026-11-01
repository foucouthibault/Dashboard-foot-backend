package fr.foucouthibault.dashboardfoot.model;

import java.util.List;

/** Réponse de {@code GET /api/competitions/{id}/matches} (type frontend {@code MatchesResponse}). */
public record MatchesResponse(List<Match> matches) {

    public MatchesResponse {
        matches = Lists.immutable(matches);
    }

    /** {@code utcDate} est relayée telle quelle (ISO 8601), sans conversion. */
    public record Match(Long id, String utcDate, String status, Integer matchday,
                        Team homeTeam, Team awayTeam, Score score) {
    }

    public record Score(FullTime fullTime) {
    }

    /** Score final : {@code null} tant que le match n'est pas joué. */
    public record FullTime(Integer home, Integer away) {
    }
}
