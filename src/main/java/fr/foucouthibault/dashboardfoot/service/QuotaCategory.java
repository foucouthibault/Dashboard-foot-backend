package fr.foucouthibault.dashboardfoot.service;

/**
 * Catégorie d'un appel sortant pour le partage du quota de football-data.org
 * ({@link FootballDataRateLimiter}).
 */
public enum QuotaCategory {
    /** Saison en cours, matchs et compétitions : peut utiliser tout le quota restant. */
    CURRENT,
    /** Saisons terminées : limitées à une part du quota, pour ne pas priver la saison en cours. */
    HISTORICAL
}
