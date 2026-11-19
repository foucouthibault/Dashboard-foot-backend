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
 * Chaque cache a une copie de secours ({@link #fallbackCacheName(String)}) : elle reçoit chaque
 * réponse réussie et la garde bien plus longtemps, pour la servir à la place d'une erreur quand
 * le quota est atteint ou que football-data.org est en panne (voir FootballDataProxyService).
 * Sa durée de vie dépend de la nature de la donnée : une compétition ou une saison terminée
 * reste utile plusieurs jours, des matchs vieux de plusieurs jours tromperaient le visiteur.
 *
 * Les durées sont mesurées par un {@link Ticker} : celui du système en production, un ticker
 * simulé dans les tests ({@link #cacheManager(Ticker)}). L'âge d'une copie, renvoyé dans
 * l'en-tête {@code Age}, est lu sur le même ticker.
 */
@Configuration
public class CacheConfig {

    public static final String COMPETITIONS_CACHE = "footballDataCompetitions";
    public static final String STATIC_CACHE = "footballData";
    public static final String CURRENT_SEASON_CACHE = "footballDataCurrentSeason";
    public static final String HISTORICAL_SEASON_CACHE = "footballDataHistorical";

    private static final String FALLBACK_SUFFIX = "Fallback";

    private final Duration competitionTtl;
    private final Duration staticTtl;
    private final Duration currentSeasonTtl;
    private final Duration historicalSeasonTtl;
    private final Duration competitionFallbackTtl;
    private final Duration staticFallbackTtl;
    private final Duration currentSeasonFallbackTtl;
    private final Duration historicalSeasonFallbackTtl;

    public CacheConfig(
        @Value("${football-data.cache.competition-ttl-hours:24}") long competitionTtlHours,
        @Value("${football-data.cache.static-ttl-seconds:60}") long staticTtlSeconds,
        @Value("${football-data.cache.current-season-ttl-seconds:300}") long currentSeasonTtlSeconds,
        @Value("${football-data.cache.historical-season-ttl-hours:720}") long historicalSeasonTtlHours,
        @Value("${football-data.cache.fallback.competition-ttl-hours:168}") long competitionFallbackTtlHours,
        @Value("${football-data.cache.fallback.static-ttl-hours:6}") long staticFallbackTtlHours,
        @Value("${football-data.cache.fallback.current-season-ttl-hours:24}") long currentSeasonFallbackTtlHours,
        @Value("${football-data.cache.fallback.historical-season-ttl-hours:1440}") long historicalSeasonFallbackTtlHours
    ) {
        this.competitionTtl = Duration.ofHours(competitionTtlHours);
        this.staticTtl = Duration.ofSeconds(staticTtlSeconds);
        this.currentSeasonTtl = Duration.ofSeconds(currentSeasonTtlSeconds);
        this.historicalSeasonTtl = Duration.ofHours(historicalSeasonTtlHours);
        this.competitionFallbackTtl = Duration.ofHours(competitionFallbackTtlHours);
        this.staticFallbackTtl = Duration.ofHours(staticFallbackTtlHours);
        this.currentSeasonFallbackTtl = Duration.ofHours(currentSeasonFallbackTtlHours);
        this.historicalSeasonFallbackTtl = Duration.ofHours(historicalSeasonFallbackTtlHours);
    }

    /** Nom du cache qui garde la copie de secours du cache {@code cacheName}. */
    public static String fallbackCacheName(String cacheName) {
        return cacheName + FALLBACK_SUFFIX;
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
        // Copies de secours : mêmes bornes en nombre d'entrées, durées bien plus longues.
        manager.registerCustomCache(fallbackCacheName(COMPETITIONS_CACHE), cache(competitionFallbackTtl, 50, ticker));
        manager.registerCustomCache(fallbackCacheName(STATIC_CACHE), cache(staticFallbackTtl, 100, ticker));
        manager.registerCustomCache(fallbackCacheName(CURRENT_SEASON_CACHE), cache(currentSeasonFallbackTtl, 150, ticker));
        manager.registerCustomCache(fallbackCacheName(HISTORICAL_SEASON_CACHE), cache(historicalSeasonFallbackTtl, 300, ticker));
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
