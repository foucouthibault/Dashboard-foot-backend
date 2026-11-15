package fr.foucouthibault.dashboardfoot.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Quatre caches avec des durées de vie différentes plutôt qu'un TTL unique, pour ne consommer
 * le quota de football-data.org (10 appels/minute partagés) que pour des données qui ont pu changer :
 * - footballDataCompetitions : /competitions/{id} (nom, code, emblème), qui ne changent
 *   pratiquement jamais ; l'accueil en demande 6 à chaque visite
 * - footballData : /matches, qui évolue pendant les matchs
 * - footballDataCurrentSeason : standings/scorers pour la saison en cours, qui évoluent
 *   au fil des matchs
 * - footballDataHistorical : standings/scorers pour une saison terminée, qui ne changent
 *   plus jamais une fois la saison close
 *
 * Les durées sont mesurées par un {@link Ticker} : celui du système en production, un ticker
 * simulé dans les tests ({@link #cacheManager(Ticker)}).
 */
@Configuration
public class CacheConfig {

    public static final String COMPETITIONS_CACHE = "footballDataCompetitions";
    public static final String STATIC_CACHE = "footballData";
    public static final String CURRENT_SEASON_CACHE = "footballDataCurrentSeason";
    public static final String HISTORICAL_SEASON_CACHE = "footballDataHistorical";

    private final Duration competitionTtl;
    private final Duration staticTtl;
    private final Duration currentSeasonTtl;
    private final Duration historicalSeasonTtl;

    public CacheConfig(
        @Value("${football-data.cache.competition-ttl-hours:24}") long competitionTtlHours,
        @Value("${football-data.cache.static-ttl-seconds:60}") long staticTtlSeconds,
        @Value("${football-data.cache.current-season-ttl-seconds:300}") long currentSeasonTtlSeconds,
        @Value("${football-data.cache.historical-season-ttl-hours:720}") long historicalSeasonTtlHours
    ) {
        this.competitionTtl = Duration.ofHours(competitionTtlHours);
        this.staticTtl = Duration.ofSeconds(staticTtlSeconds);
        this.currentSeasonTtl = Duration.ofSeconds(currentSeasonTtlSeconds);
        this.historicalSeasonTtl = Duration.ofHours(historicalSeasonTtlHours);
    }

    @Bean
    public CacheManager cacheManager() {
        return cacheManager(Ticker.systemTicker());
    }

    /** Même configuration, avec un ticker choisi : les tests y passent un temps simulé. */
    public CacheManager cacheManager(Ticker ticker) {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // Quelques compétitions affichées, quelques saisons consultées par compétition.
        manager.registerCustomCache(COMPETITIONS_CACHE, cache(competitionTtl, 50, ticker));
        manager.registerCustomCache(STATIC_CACHE, cache(staticTtl, 100, ticker));
        manager.registerCustomCache(CURRENT_SEASON_CACHE, cache(currentSeasonTtl, 150, ticker));
        manager.registerCustomCache(HISTORICAL_SEASON_CACHE, cache(historicalSeasonTtl, 300, ticker));
        return manager;
    }

    private static com.github.benmanes.caffeine.cache.Cache<Object, Object> cache(
        Duration ttl, long maximumSize, Ticker ticker) {
        return Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .expireAfterWrite(ttl)
            .ticker(ticker)
            .build();
    }
}
