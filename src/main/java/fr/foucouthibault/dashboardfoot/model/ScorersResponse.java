package fr.foucouthibault.dashboardfoot.model;

import java.util.List;

/**
 * Réponse de {@code GET /api/competitions/{id}/scorers} (type frontend {@code ScorersResponse}).
 * {@code count} n'est pas lu par le frontend mais reste cohérent avec la liste après découpage.
 */
public record ScorersResponse(Integer count, List<Scorer> scorers) {

    public ScorersResponse {
        scorers = Lists.immutable(scorers);
    }

    /**
     * Garde les {@code limit} premiers buteurs, sans modifier cette instance (partagée par le
     * cache). Un {@code count} absent de la réponse amont reste absent.
     */
    public ScorersResponse limitedTo(int limit) {
        if (limit >= scorers.size()) {
            return this;
        }
        List<Scorer> kept = scorers.subList(0, Math.max(0, limit));
        return new ScorersResponse(count == null ? null : kept.size(), kept);
    }

    public record Scorer(Player player, Team team, Integer goals, Integer assists, Integer penalties) {
    }

    public record Player(Long id, String name, String nationality) {
    }
}
