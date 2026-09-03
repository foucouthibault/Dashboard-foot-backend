package com.example.Dashboard_foot.service;

import com.example.Dashboard_foot.config.CacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.time.Year;
import java.util.Collections;

/**
 * Centralise l'appel à football-data.org : limitation de débit, cache différencié
 * par nature de donnée, et normalisation des erreurs. Extrait du contrôleur pour que
 * les appels de cache passent par le proxy Spring (l'auto-invocation casse @Cacheable),
 * même si ici le cache est piloté manuellement via CacheManager pour choisir la bonne
 * durée de vie selon que la saison demandée est terminée ou en cours.
 */
@Service
public class FootballDataProxyService {

    private static final Logger log = LoggerFactory.getLogger(FootballDataProxyService.class);

    // Nombre max de buteurs qu'on demande à l'API et qu'on met en cache : on découpe
    // ensuite localement selon le `limit` demandé par le client, pour qu'un changement
    // de `limit` côté frontend ne déclenche jamais un nouvel appel amont.
    private static final int SCORERS_MAX_LIMIT = 50;

    private final RestTemplate restTemplate;
    private final CacheManager cacheManager;
    private final FootballDataRateLimiter rateLimiter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${football-data.api.base-url:https://api.football-data.org/v4}")
    private String apiBaseUrl;

    @Value("${football-data.api.token:}")
    private String apiToken;

    public FootballDataProxyService(RestTemplate restTemplate, CacheManager cacheManager,
                                     FootballDataRateLimiter rateLimiter) {
        this.restTemplate = restTemplate;
        this.cacheManager = cacheManager;
        this.rateLimiter = rateLimiter;
    }

    public ResponseEntity<String> getCompetition(String id, HttpHeaders headers) {
        String url = apiBaseUrl + "/competitions/" + id;
        return cached(CacheConfig.STATIC_CACHE, "competition_" + id, url, headers);
    }

    public ResponseEntity<String> getStandings(String id, String season, HttpHeaders headers) {
        String url = apiBaseUrl + "/competitions/" + id + "/standings";
        if (season != null && !season.isEmpty()) {
            url += "?season=" + season;
        }
        String cacheName = isHistoricalSeason(season) ? CacheConfig.HISTORICAL_SEASON_CACHE : CacheConfig.CURRENT_SEASON_CACHE;
        String cacheKey = "standings_" + id + "_" + normalizeSeason(season);
        return cached(cacheName, cacheKey, url, headers);
    }

    public ResponseEntity<String> getMatches(String id, HttpHeaders headers) {
        String url = apiBaseUrl + "/competitions/" + id + "/matches";
        return cached(CacheConfig.STATIC_CACHE, "matches_" + id, url, headers);
    }

    public ResponseEntity<String> getScorers(String id, Integer limit, String season, HttpHeaders headers) {
        String url = apiBaseUrl + "/competitions/" + id + "/scorers?limit=" + SCORERS_MAX_LIMIT;
        if (season != null && !season.isEmpty()) {
            url += "&season=" + season;
        }
        String cacheName = isHistoricalSeason(season) ? CacheConfig.HISTORICAL_SEASON_CACHE : CacheConfig.CURRENT_SEASON_CACHE;
        String cacheKey = "scorers_" + id + "_" + normalizeSeason(season);
        ResponseEntity<String> full = cached(cacheName, cacheKey, url, headers);
        return trimScorers(full, limit);
    }

    private String normalizeSeason(String season) {
        return (season == null || season.isEmpty()) ? "current" : season;
    }

    /**
     * Une saison est considérée terminée (donc immuable) dès que son année de début est
     * antérieure à l'année civile en cours. C'est approximatif autour du changement de
     * saison (juillet-août), mais cohérent avec la façon dont le frontend calcule déjà
     * ses options de saison à partir de l'année civile : au pire, une saison encore
     * active reste en cache un peu plus longtemps que nécessaire.
     */
    private boolean isHistoricalSeason(String season) {
        if (season == null || season.isEmpty()) {
            return false;
        }
        try {
            return Integer.parseInt(season) < Year.now().getValue();
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private ResponseEntity<String> cached(String cacheName, String key, String url, HttpHeaders incomingHeaders) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            Cache.ValueWrapper wrapper = cache.get(key);
            if (wrapper != null) {
                @SuppressWarnings("unchecked")
                ResponseEntity<String> hit = (ResponseEntity<String>) wrapper.get();
                return hit;
            }
        }

        ResponseEntity<String> response = fetch(url, incomingHeaders);
        if (cache != null && response.getStatusCode().is2xxSuccessful()) {
            // On ne met en cache que les réponses réussies : une erreur transitoire
            // (429, panne réseau...) ne doit pas rester figée jusqu'à expiration du TTL.
            cache.put(key, response);
        }
        return response;
    }

    private ResponseEntity<String> fetch(String url, HttpHeaders incomingHeaders) {
        if (!rateLimiter.tryAcquire()) {
            long retryAfter = rateLimiter.secondsUntilNextSlot();
            log.warn("Quota interne d'appels à football-data.org atteint, requête refusée ({}s avant réessai) pour {}", retryAfter, url);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(retryAfter))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"Trop de requêtes vers l'API football-data.org, réessayez dans quelques secondes\"}");
        }

        HttpHeaders requestHeaders = new HttpHeaders();
        if (incomingHeaders != null) {
            incomingHeaders.forEach((headerName, values) -> {
                if (headerName.equalsIgnoreCase("accept") || headerName.equalsIgnoreCase("accept-language")) {
                    requestHeaders.put(headerName, values);
                }
            });
        }
        requestHeaders.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));
        if (apiToken != null && !apiToken.isEmpty()) {
            requestHeaders.set("X-Auth-Token", apiToken);
        }

        HttpEntity<String> entity = new HttpEntity<>(requestHeaders);

        try {
            ResponseEntity<String> response = restTemplate.exchange(URI.create(url), HttpMethod.GET, entity, String.class);

            HttpHeaders responseHeaders = new HttpHeaders();
            response.getHeaders().forEach((headerName, values) -> {
                if (!headerName.equalsIgnoreCase("Transfer-Encoding") && !headerName.equalsIgnoreCase("Content-Encoding")) {
                    responseHeaders.put(headerName, values);
                }
            });
            responseHeaders.setContentType(MediaType.APPLICATION_JSON);

            return new ResponseEntity<>(response.getBody(), responseHeaders, response.getStatusCode());
        } catch (HttpStatusCodeException e) {
            // L'API a répondu avec un statut d'erreur (saison hors plan, quota atteint...) :
            // on transmet ce statut et son message plutôt que de le masquer en 502 générique.
            log.warn("football-data.org a répondu {} pour {}: {}", e.getStatusCode(), url, e.getResponseBodyAsString());
            return ResponseEntity.status(e.getStatusCode())
                .contentType(MediaType.APPLICATION_JSON)
                .body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Échec de l'appel à {}", url, e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body("{\"error\":\"Failed to fetch data from API\"}");
        }
    }

    private ResponseEntity<String> trimScorers(ResponseEntity<String> response, Integer limit) {
        if (limit == null || limit >= SCORERS_MAX_LIMIT
            || !response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            return response;
        }
        try {
            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode scorersNode = root.get("scorers");
            if (scorersNode == null || !scorersNode.isArray() || scorersNode.size() <= limit || !(root instanceof ObjectNode objectRoot)) {
                return response;
            }

            ArrayNode trimmed = objectMapper.createArrayNode();
            for (int i = 0; i < limit; i++) {
                trimmed.add(scorersNode.get(i));
            }
            objectRoot.set("scorers", trimmed);
            if (objectRoot.has("count")) {
                objectRoot.put("count", trimmed.size());
            }

            HttpHeaders headers = new HttpHeaders();
            headers.putAll(response.getHeaders());
            headers.remove(HttpHeaders.CONTENT_LENGTH);

            return new ResponseEntity<>(objectMapper.writeValueAsString(objectRoot), headers, response.getStatusCode());
        } catch (Exception e) {
            log.warn("Impossible de tronquer la réponse des buteurs, renvoi de la liste complète", e);
            return response;
        }
    }
}
