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
import io.sentry.Sentry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
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
import java.time.Duration;
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
 *
 * Chaque réponse réussie est aussi copiée dans un cache de secours à longue durée de vie
 * ({@link CacheConfig#fallbackCacheName(String)}). Quand le chargement échoue pour une raison
 * passagère (quota atteint, {@code 429} ou {@code 5xx} de football-data.org, panne réseau,
 * réponse illisible), cette copie est servie à la place de l'erreur et son âge est signalé par
 * l'en-tête {@code Age} ({@link StaleResponse}). Un {@code 400}/{@code 403}/{@code 404} amont
 * est une vraie réponse, pas une panne : il reste relayé.
 *
 * Les lignes de log notables portent un champ {@code event} au nom stable (constantes
 * {@code EVENT_*}), visible dans les logs JSON de production : on peut compter ou filtrer un
 * type d'événement sans dépendre du texte du message, qui reste libre.
 */
@Service
public class FootballDataProxyService {

    private static final Logger log = LoggerFactory.getLogger(FootballDataProxyService.class);

    // Nombre max de buteurs qu'on demande à l'API et qu'on met en cache : on découpe
    // ensuite localement selon le `limit` demandé par le client, pour qu'un changement
    // de `limit` côté frontend ne déclenche jamais un nouvel appel amont.
    private static final int SCORERS_MAX_LIMIT = 50;

    /** Nom du champ des événements nommés dans les logs structurés. */
    static final String EVENT = "event";
    static final String EVENT_QUOTA_ATTEINT = "quota_atteint";
    static final String EVENT_AMONT_ERREUR = "amont_erreur";
    static final String EVENT_AMONT_INDISPONIBLE = "amont_indisponible";
    static final String EVENT_URL_REFUSEE = "url_refusee";
    static final String EVENT_CACHE_MANQUE = "cache_manque";
    static final String EVENT_DONNEE_PERIMEE_SERVIE = "donnee_perimee_servie";

    /** Raisons de servir une copie de secours (champ {@code reason} de l'événement). */
    static final String REASON_QUOTA_INTERNE = "quota_interne";
    static final String REASON_AMONT_ERREUR = "amont_erreur";
    static final String REASON_AMONT_INDISPONIBLE = "amont_indisponible";

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
        return cached(CacheConfig.COMPETITIONS_CACHE, "competition_" + id, url, headers, CompetitionResponse.class);
    }

    public StandingsResponse getStandings(String id, String season, HttpHeaders headers) {
        String url = UriComponentsBuilder.fromUriString(apiBaseUrl + "/competitions/" + id + "/standings")
            .queryParamIfPresent("season", seasonParam(season))
            .build()
            .encode()
            .toUriString();
        String cacheName = isHistoricalSeason(season) ? CacheConfig.HISTORICAL_SEASON_CACHE : CacheConfig.CURRENT_SEASON_CACHE;
        String cacheKey = CacheConfig.seasonKey("standings", id, normalizeSeason(season));
        return cached(cacheName, cacheKey, url, headers, StandingsResponse.class);
    }

    public MatchesResponse getMatches(String id, HttpHeaders headers) {
        String url = apiBaseUrl + "/competitions/" + id + "/matches";
        return cached(CacheConfig.STATIC_CACHE, CacheConfig.matchesKey(id), url, headers, MatchesResponse.class);
    }

    public ScorersResponse getScorers(String id, Integer limit, String season, HttpHeaders headers) {
        String url = UriComponentsBuilder.fromUriString(apiBaseUrl + "/competitions/" + id + "/scorers")
            .queryParam("limit", SCORERS_MAX_LIMIT)
            .queryParamIfPresent("season", seasonParam(season))
            .build()
            .encode()
            .toUriString();
        String cacheName = isHistoricalSeason(season) ? CacheConfig.HISTORICAL_SEASON_CACHE : CacheConfig.CURRENT_SEASON_CACHE;
        String cacheKey = CacheConfig.seasonKey("scorers", id, normalizeSeason(season));
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

    /**
     * Sert la réponse depuis le cache, ou la charge une seule fois par clé : avec Caffeine,
     * {@link Cache#get(Object, java.util.concurrent.Callable)} est atomique, donc des requêtes
     * simultanées sur une clé absente attendent le chargement en cours au lieu de lancer chacune
     * leur appel sortant (accueil qui demande ses 6 compétitions, deux visiteurs en même temps).
     * Un hit ne passe ni par le limiteur ni par l'API.
     *
     * Si le chargement échoue, la copie de secours est servie quand l'échec est passager
     * ({@link #fallbackReason(RuntimeException)}) ; elle n'est pas remise dans le cache
     * principal, pour qu'un nouvel appel soit tenté dès qu'un créneau se libère.
     */
    private <T> T cached(String cacheName, String key, String url, HttpHeaders incomingHeaders, Class<T> type) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null) {
            return load(cacheName, key, url, incomingHeaders, type);
        }
        Cache fallback = cacheManager.getCache(CacheConfig.fallbackCacheName(cacheName));
        try {
            // fetch ne renvoie qu'une réponse réussie et lisible : toute erreur lève une exception
            // pendant le chargement, et Caffeine ne garde alors rien en cache (une erreur
            // transitoire ne reste pas figée).
            return cache.get(key, () -> {
                T value = load(cacheName, key, url, incomingHeaders, type);
                if (fallback != null) {
                    fallback.put(key, value);
                }
                return value;
            });
        } catch (Cache.ValueRetrievalException e) {
            // Spring enveloppe l'exception du chargement : on relance l'originale pour
            // qu'ApiExceptionHandler garde son statut (429, statut amont, 502…).
            RuntimeException failure = switch (e.getCause()) {
                case RuntimeException cause -> cause;
                case Error error -> throw error;
                case null, default -> new UpstreamUnavailableException(e.getCause());
            };
            return fallbackCopy(cacheName, fallback, key, type, failure).orElseThrow(() -> failure);
        }
    }

    /**
     * Copie de secours de {@code key}, si l'échec le permet et qu'elle existe encore. La servir
     * ne consomme pas de quota. Une panne de football-data.org reste signalée à Sentry ; un
     * refus du limiteur interne non (comportement attendu, filtré par SentryEventFilter).
     */
    private <T> Optional<T> fallbackCopy(String cacheName, Cache fallback, String key, Class<T> type,
                                         RuntimeException failure) {
        String reason = fallbackReason(failure);
        if (reason == null || fallback == null) {
            return Optional.empty();
        }
        Object copy = Optional.ofNullable(fallback.get(key)).map(Cache.ValueWrapper::get).orElse(null);
        if (!type.isInstance(copy)) {
            return Optional.empty();
        }
        Duration age = ageOf(fallback, key);
        log.atWarn()
            .addKeyValue(EVENT, EVENT_DONNEE_PERIMEE_SERVIE)
            .addKeyValue("cache", cacheName)
            .addKeyValue("cacheKey", forLog(key))
            .addKeyValue("reason", reason)
            .addKeyValue("ageSeconds", age.toSeconds())
            .log("Copie de secours servie pour {} ({}, âge {}s)", forLog(key), reason, age.toSeconds());
        if (!(failure instanceof RateLimitExceededException)) {
            Sentry.captureException(failure);
        }
        StaleResponse.markCurrentRequest(age);
        return Optional.of(type.cast(copy));
    }

    /**
     * Raison de servir la copie de secours, ou {@code null} si l'échec doit atteindre le client :
     * un {@code 400}/{@code 403}/{@code 404} de football-data.org, une URL refusée.
     */
    static String fallbackReason(RuntimeException failure) {
        return switch (failure) {
            case RateLimitExceededException ignored -> REASON_QUOTA_INTERNE;
            case UpstreamUnavailableException ignored -> REASON_AMONT_INDISPONIBLE;
            case UpstreamErrorException e when e.getStatus().isSameCodeAs(HttpStatus.TOO_MANY_REQUESTS)
                || e.getStatus().is5xxServerError() -> REASON_AMONT_ERREUR;
            default -> null;
        };
    }

    /** Temps écoulé depuis l'écriture de la copie, mesuré par le ticker du cache. */
    private static Duration ageOf(Cache fallback, String key) {
        if (fallback instanceof CaffeineCache caffeine) {
            return caffeine.getNativeCache().policy().expireAfterWrite()
                .flatMap(expiration -> expiration.ageOf(key))
                .orElse(Duration.ZERO);
        }
        return Duration.ZERO;
    }

    private <T> T load(String cacheName, String key, String url, HttpHeaders incomingHeaders, Class<T> type) {
        // INFO plutôt que DEBUG : chaque manque mène à un appel sortant (au plus 10 par minute) ou à
        // un refus du limiteur, donc le volume reste borné, et ces lignes comptent les appels amont.
        log.atInfo()
            .addKeyValue(EVENT, EVENT_CACHE_MANQUE)
            .addKeyValue("cache", cacheName)
            .addKeyValue("cacheKey", forLog(key))
            .log("Absent du cache {} : {}", cacheName, forLog(key));
        return fetch(url, incomingHeaders, type);
    }

    private <T> T fetch(String url, HttpHeaders incomingHeaders, Class<T> type) {
        if (!rateLimiter.tryAcquire()) {
            long retryAfter = rateLimiter.secondsUntilNextSlot();
            log.atWarn()
                .addKeyValue(EVENT, EVENT_QUOTA_ATTEINT)
                .addKeyValue("retryAfter", retryAfter)
                .log("Quota interne d'appels à football-data.org atteint, requête refusée ({}s avant réessai) pour {}", retryAfter, forLog(url));
            throw new RateLimitExceededException(retryAfter);
        }

        URI targetUri;
        try {
            targetUri = URI.create(url);
        } catch (IllegalArgumentException e) {
            logUnavailable("Échec de l'appel à {}", url, e);
            throw new UpstreamUnavailableException(e);
        }
        if (!isAllowedApiUri(targetUri)) {
            log.atWarn()
                .addKeyValue(EVENT, EVENT_URL_REFUSEE)
                .log("URL sortante refusée (contrôle SSRF): {}", forLog(url));
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
            log.atWarn()
                .addKeyValue(EVENT, EVENT_AMONT_ERREUR)
                .addKeyValue("upstreamStatus", e.getStatusCode().value())
                .log("football-data.org a répondu {} pour {}: {}", e.getStatusCode(), forLog(url), forLog(e.getResponseBodyAsString()));
            throw new UpstreamErrorException(e.getStatusCode());
        } catch (RuntimeException e) {
            logUnavailable("Échec de l'appel à {}", url, e);
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
            logUnavailable("Réponse illisible de football-data.org pour {}", url, e);
            throw new UpstreamUnavailableException(e);
        }
        if (value == null) {
            logUnavailable("Réponse vide de football-data.org pour {}", url, null);
            throw new UpstreamUnavailableException(null);
        }
        return value;
    }

    /** Panne amont (réseau, réponse vide ou illisible) : le client recevra un 502. */
    private static void logUnavailable(String message, String url, Exception cause) {
        log.atWarn()
            .addKeyValue(EVENT, EVENT_AMONT_INDISPONIBLE)
            .setCause(cause)
            .log(message, forLog(url));
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
