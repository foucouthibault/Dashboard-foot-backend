package fr.foucouthibault.dashboardfoot.model;

import io.swagger.v3.oas.annotations.media.Schema;

/** Réponse de {@code GET /api/competitions/{id}} (type frontend {@code Competition}). */
public record CompetitionResponse(Long id, String name, String code, @Schema(nullable = true) String emblem) {
}
