package fr.foucouthibault.dashboardfoot.service;

import fr.foucouthibault.dashboardfoot.config.CacheConfig;
import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamErrorException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUnavailableException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUrlRejectedException;
import fr.foucouthibault.dashboardfoot.model.CompetitionResponse;
import fr.foucouthibault.dashboardfoot.model.MatchesResponse;
import fr.foucouthibault.dashboardfoot.model.ScorersResponse;
import fr.foucouthibault.dashboardfoot.model.StandingsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Year;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Centralise l'appel à football-data.org : limitation de débit, cache différencié
 * par nature de donnée, désérialisation en réponses typées et normalisation des erreurs.
 * Le cache est piloté manuellement via CacheManager pour choisir la bonne durée de vie
 * selon que la saison demandée est terminée ou en cours ; il stocke les réponses typées.
 * Les erreurs sont signalées par des exceptions, traduites en HTTP par ApiExceptionHandler.
 */
@Service
public class FootballDataProxyService {

    private static final Logger log = LoggerFactory.getLogger(FootballDataProxyService.class);

    // Nombre max de buteurs qu'on demande à l'API et qu'on met en cache : on découpe
    // ensuite localement selon le `limit` demandé par le client, pour qu'un changement
    // de `limit` côté frontend ne déclenche jamais un nouvel appel amont.
    private static final int SCORERS_MAX_LIMIT = 50;

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

    private final RestTemplate restTemplate;
    private final CacheManager cacheManager;
    private final FootballDataRateLimiter rateLimiter;
    // Seuls les champs des records sont lus : les autres champs de l'API sont ignorés.
    private final ObjectMapper objectMapper = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

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

    public CompetitionResponse getCompetition(String id, HttpHeaders headers) {
        String url = apiBaseUrl + "/competitions/" + id;
        return cached(CacheConfig.STATIC_CACHE, "competition_" + id, url, headers, CompetitionResponse.class);
    }

    public StandingsResponse getStandings(String id, String season, HttpHeaders headers) {
        String url = UriComponentsBuilder.fromUriString(apiBaseUrl + "/competitions/" + id + "/standings")
            .queryParamIfPresent("season", seasonParam(season))
            .build()
            .encode()
            .toUriString();
        String cacheName = isHistoricalSeason(season) ? CacheConfig.HISTORICAL_SEASON_CACHE : CacheConfig.CURRENT_SEASON_CACHE;
        String cacheKey = "standings_" + id + "_" + normalizeSeason(season);
        return cached(cacheName, cacheKey, url, headers, StandingsResponse.class);
    }

    public MatchesResponse getMatches(String id, HttpHeaders headers) {
        String url = apiBaseUrl + "/competitions/" + id + "/matches";
        return cached(CacheConfig.STATIC_CACHE, "matches_" + id, url, headers, MatchesResponse.class);
    }

    public ScorersResponse getScorers(String id, Integer limit, String season, HttpHeaders headers) {
        String url = UriComponentsBuilder.fromUriString(apiBaseUrl + "/competitions/" + id + "/scorers")
            .queryParam("limit", SCORERS_MAX_LIMIT)
            .queryParamIfPresent("season", seasonParam(season))
            .build()
            .encode()
            .toUriString();
        String cacheName = isHistoricalSeason(season) ? CacheConfig.HISTORICAL_SEASON_CACHE : CacheConfig.CURRENT_SEASON_CACHE;
        String cacheKey = "scorers_" + id + "_" + normalizeSeason(season);
        ScorersResponse full = cached(cacheName, cacheKey, url, headers, ScorersResponse.class);
        return (limit == null || limit >= SCORERS_MAX_LIMIT) ? full : full.limitedTo(limit);
    }

    private Optional<String> seasonParam(String season) {
        return Optional.ofNullable(season).filter(s -> !s.isEmpty());
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

    private <T> T cached(String cacheName, String key, String url, HttpHeaders incomingHeaders, Class<T> type) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            T hit = cache.get(key, type);
            if (hit != null) {
                return hit;
            }
        }

        // fetch ne renvoie qu'une réponse réussie et lisible : toute erreur lève une exception
        // et n'est donc jamais mise en cache (une erreur transitoire ne reste pas figée).
        T response = fetch(url, incomingHeaders, type);
        if (cache != null) {
            cache.put(key, response);
        }
        return response;
    }

    private <T> T fetch(String url, HttpHeaders incomingHeaders, Class<T> type) {
        if (!rateLimiter.tryAcquire()) {
            long retryAfter = rateLimiter.secondsUntilNextSlot();
            log.warn("Quota interne d'appels à football-data.org atteint, requête refusée ({}s avant réessai) pour {}", retryAfter, forLog(url));
            throw new RateLimitExceededException(retryAfter);
        }

        URI targetUri;
        try {
            targetUri = URI.create(url);
        } catch (IllegalArgumentException e) {
            log.warn("Échec de l'appel à {}", forLog(url), e);
            throw new UpstreamUnavailableException(e);
        }
        if (!isAllowedApiUri(targetUri)) {
            log.warn("URL sortante refusée (contrôle SSRF): {}", forLog(url));
            throw new UpstreamUrlRejectedException();
        }

        HttpEntity<String> entity = new HttpEntity<>(outgoingHeaders(incomingHeaders));
        String body;
        try {
            body = restTemplate.exchange(targetUri, HttpMethod.GET, entity, String.class).getBody();
        } catch (HttpStatusCodeException e) {
            // L'API a répondu avec un statut d'erreur (saison hors plan, quota atteint...) :
            // on transmet ce statut plutôt que de le masquer en 502 générique. Son message ne
            // va que dans les logs.
            log.warn("football-data.org a répondu {} pour {}: {}", e.getStatusCode(), forLog(url), forLog(e.getResponseBodyAsString()));
            throw new UpstreamErrorException(e.getStatusCode());
        } catch (RuntimeException e) {
            log.warn("Échec de l'appel à {}", forLog(url), e);
            throw new UpstreamUnavailableException(e);
        }
        return read(body, type, url);
    }

    /** Seuls Accept-Language et le jeton du serveur sont envoyés ; Accept est forcé à JSON. */
    private HttpHeaders outgoingHeaders(HttpHeaders incomingHeaders) {
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
        return requestHeaders;
    }

    /** Désérialise une réponse réussie ; un corps vide ou illisible est traité comme une panne. */
    private <T> T read(String body, Class<T> type, String url) {
        T value;
        try {
            value = body == null ? null : objectMapper.readValue(body, type);
        } catch (JacksonException e) {
            log.warn("Réponse illisible de football-data.org pour {}", forLog(url), e);
            throw new UpstreamUnavailableException(e);
        }
        if (value == null) {
            log.warn("Réponse vide de football-data.org pour {}", forLog(url));
            throw new UpstreamUnavailableException(null);
        }
        return value;
    }

    /**
     * Neutralise les caractères de contrôle (retours à la ligne, tabulations, séquences
     * d'échappement) d'une valeur venant du client ou de l'API amont avant de l'écrire dans
     * les logs : sans cela, un `id` contenant un retour à la ligne pourrait fabriquer une
     * fausse ligne de log. Le contrôleur rejette déjà ces `id`, mais le service ne doit pas
     * dépendre de cette validation.
     */
    static String forLog(String value) {
        return value == null ? null : CONTROL_CHARACTERS.matcher(value).replaceAll("_");
    }

    private boolean isAllowedApiUri(URI targetUri) {
        try {
            URI baseUri = URI.create(apiBaseUrl);
            String targetScheme = targetUri.getScheme();
            String baseScheme = baseUri.getScheme();
            if (targetScheme == null || baseScheme == null) {
                return false;
            }
            if (!(targetScheme.equalsIgnoreCase("http") || targetScheme.equalsIgnoreCase("https"))) {
                return false;
            }
            if (!targetScheme.equalsIgnoreCase(baseScheme)) {
                return false;
            }
            String targetHost = targetUri.getHost();
            String baseHost = baseUri.getHost();
            if (targetHost == null || baseHost == null) {
                return false;
            }
            if (!targetHost.equalsIgnoreCase(baseHost)) {
                return false;
            }
            return effectivePort(targetUri) == effectivePort(baseUri);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
