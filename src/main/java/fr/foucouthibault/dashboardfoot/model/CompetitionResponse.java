package fr.foucouthibault.dashboardfoot.model;

/** Réponse de {@code GET /api/competitions/{id}} (type frontend {@code Competition}). */
public record CompetitionResponse(Long id, String name, String code, String emblem) {
}
