package com.example.Dashboard_foot.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Trois caches avec des durées de vie différentes plutôt qu'un TTL unique :
 * - footballData : ressources sans notion de saison (/competitions/{id}, /matches)
 * - footballDataCurrentSeason : standings/scorers pour la saison en cours, qui évoluent
 *   au fil des matchs
 * - footballDataHistorical : standings/scorers pour une saison terminée, qui ne changent
 *   plus jamais une fois la saison close
 */
@Configuration
public class CacheConfig {

    @Value("${football-data.cache.static-ttl-seconds:60}")
    private long staticTtlSeconds;

    @Value("${football-data.cache.current-season-ttl-seconds:300}")
    private long currentSeasonTtlSeconds;

    @Value("${football-data.cache.historical-season-ttl-hours:24}")
    private long historicalSeasonTtlHours;

    public static final String STATIC_CACHE = "footballData";
    public static final String CURRENT_SEASON_CACHE = "footballDataCurrentSeason";
    public static final String HISTORICAL_SEASON_CACHE = "footballDataHistorical";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.registerCustomCache(STATIC_CACHE,
            Caffeine.newBuilder()
                .maximumSize(100)
                .expireAfterWrite(Duration.ofSeconds(staticTtlSeconds))
                .build());
        manager.registerCustomCache(CURRENT_SEASON_CACHE,
            Caffeine.newBuilder()
                .maximumSize(150)
                .expireAfterWrite(Duration.ofSeconds(currentSeasonTtlSeconds))
                .build());
        manager.registerCustomCache(HISTORICAL_SEASON_CACHE,
            Caffeine.newBuilder()
                .maximumSize(300)
                .expireAfterWrite(Duration.ofHours(historicalSeasonTtlHours))
                .build());
        return manager;
    }
}
