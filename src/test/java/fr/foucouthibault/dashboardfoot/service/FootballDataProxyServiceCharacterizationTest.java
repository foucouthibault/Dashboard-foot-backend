package fr.foucouthibault.dashboardfoot.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import fr.foucouthibault.dashboardfoot.config.CacheConfig;
import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamErrorException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUnavailableException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUrlRejectedException;
import fr.foucouthibault.dashboardfoot.model.CompetitionResponse;
import fr.foucouthibault.dashboardfoot.model.MatchesResponse;
import fr.foucouthibault.dashboardfoot.model.ScorersResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests de caractérisation du service : ils décrivent le comportement actuel (contrôle SSRF,
 * en-têtes relayés, désérialisation, découpage des buteurs) sans le juger, pour sécuriser
 * les refactos #34 et #35. Complètent {@link FootballDataProxyServiceTest}.
 *
 * Depuis #34, le service renvoie des records et signale les erreurs par des exceptions,
 * transformées en réponses HTTP par ApiExceptionHandler.
 */
@ExtendWith(MockitoExtension.class)
class FootballDataProxyServiceCharacterizationTest {

    private static final String BASE = "https://api.football-data.org/v4";
    /** Saison terminée, calculée comme dans FootballDataProxyServiceTest. */
    private static final String HISTORICAL_SEASON = String.valueOf(Year.now().getValue() - 5);
    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private RestTemplate restTemplate;

    private CacheManager cacheManager;
    private FootballDataProxyService service;

    @BeforeEach
    void setUp() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        Caffeine<Object, Object> generousTtl = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(10));
        manager.registerCustomCache(CacheConfig.STATIC_CACHE, generousTtl.build());
        manager.registerCustomCache(CacheConfig.CURRENT_SEASON_CACHE, generousTtl.build());
        manager.registerCustomCache(CacheConfig.HISTORICAL_SEASON_CACHE, generousTtl.build());
        cacheManager = manager;
        service = newService(BASE, "test-token");
    }

    private FootballDataProxyService newService(String baseUrl, String token) {
        FootballDataProxyService created =
            new FootballDataProxyService(restTemplate, cacheManager, limiter(1000));
        setField(created, "apiBaseUrl", baseUrl);
        setField(created, "apiToken", token);
        return created;
    }

    /** Limiteur réel sur une horloge figée : le quota ne dépend pas de l'heure réelle. */
    private static FootballDataRateLimiter limiter(int maxRequestsPerMinute) {
        return new FootballDataRateLimiter(maxRequestsPerMinute, Duration.ofMinutes(1), FIXED_CLOCK);
    }

    private static void setField(FootballDataProxyService target, String name, String value) {
        try {
            var field = FootballDataProxyService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private void upstreamReturns(ResponseEntity<String> response) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenReturn(response);
    }

    private static ResponseEntity<String> ok(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @SuppressWarnings("unchecked")
    private HttpEntity<String> capturedRequest() {
        ArgumentCaptor<HttpEntity<String>> captor = ArgumentCaptor.forClass(HttpEntity.class);
        verify(restTemplate).exchange(any(URI.class), eq(HttpMethod.GET), captor.capture(), eq(String.class));
        return captor.getValue();
    }

    /**
     * Appelle isAllowedApiUri (privée) directement : la plupart de ses branches de refus ne
     * sont pas atteignables par l'API publique, car l'URL sortante est toujours construite
     * à partir de apiBaseUrl (schéma, hôte et port ne peuvent donc pas en différer).
     */
    private static boolean isAllowed(FootballDataProxyService target, String targetUri) throws Exception {
        Method method = FootballDataProxyService.class.getDeclaredMethod("isAllowedApiUri", URI.class);
        method.setAccessible(true);
        try {
            return (boolean) method.invoke(target, URI.create(targetUri));
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    // ==================== SSRF : par l'API publique ====================

    private void assertRefusedAsInvalidUpstreamUrl(String baseUrl) {
        FootballDataProxyService misconfigured = newService(baseUrl, "test-token");

        assertThrows(UpstreamUrlRejectedException.class,
            () -> misconfigured.getCompetition("FL1", new HttpHeaders()));
        verifyNoInteractions(restTemplate);
    }

    @Test
    void ssrf_urlWithoutScheme_isRefusedWithoutOutgoingCall() {
        assertRefusedAsInvalidUpstreamUrl("api.football-data.org/v4");
    }

    @Test
    void ssrf_nonHttpScheme_isRefusedWithoutOutgoingCall() {
        assertRefusedAsInvalidUpstreamUrl("ftp://api.football-data.org/v4");
    }

    @Test
    void ssrf_unreadableHost_isRefusedWithoutOutgoingCall() {
        // Le « _ » rend l'autorité non analysable comme un hôte : getHost() renvoie null.
        assertRefusedAsInvalidUpstreamUrl("https://api_football-data.org/v4");
    }

    @Test
    void ssrf_refusalDoesNotConsumeCache() {
        FootballDataProxyService misconfigured = newService("ftp://api.football-data.org/v4", "test-token");

        assertThrows(UpstreamUrlRejectedException.class,
            () -> misconfigured.getCompetition("FL1", new HttpHeaders()));

        assertNull(cacheManager.getCache(CacheConfig.STATIC_CACHE).get("competition_FL1"));
    }

    @Test
    void ssrf_malformedBaseUrl_isAnUpstreamFailureWithoutLeakingTheMessage() {
        // URI.create(url) échoue avant le contrôle SSRF : c'est le catch générique qui répond.
        FootballDataProxyService misconfigured = newService("http://[invalid", "test-token");

        UpstreamUnavailableException e = assertThrows(UpstreamUnavailableException.class,
            () -> misconfigured.getCompetition("FL1", new HttpHeaders()));

        assertFalse(e.getMessage().contains("[invalid"));
        verifyNoInteractions(restTemplate);
    }

    // ==================== SSRF : isAllowedApiUri appelée directement ====================

    @Test
    void isAllowedApiUri_acceptsSameSchemeHostAndPort() throws Exception {
        assertTrue(isAllowed(service, BASE + "/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_isCaseInsensitiveOnSchemeAndHost() throws Exception {
        assertTrue(isAllowed(service, "HTTPS://API.Football-Data.ORG/v4/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_treatsExplicitDefaultPortAsTheSamePort() throws Exception {
        assertTrue(isAllowed(service, "https://api.football-data.org:443/v4/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_treatsExplicitDefaultHttpPortAsTheSamePort() throws Exception {
        FootballDataProxyService http = newService("http://api.example.org/v4", "");
        assertTrue(isAllowed(http, "http://api.example.org:80/v4/x"));
    }

    @Test
    void isAllowedApiUri_refusesMissingScheme() throws Exception {
        assertFalse(isAllowed(service, "//api.football-data.org/v4/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_refusesNonHttpScheme() throws Exception {
        assertFalse(isAllowed(service, "file://api.football-data.org/v4/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_refusesDifferentScheme() throws Exception {
        assertFalse(isAllowed(service, "http://api.football-data.org/v4/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_refusesDifferentHost() throws Exception {
        assertFalse(isAllowed(service, "https://evil.example.com/v4/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_refusesHostThatOnlyStartsWithTheBaseHost() throws Exception {
        assertFalse(isAllowed(service, "https://api.football-data.org.evil.com/v4/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_refusesDifferentPort() throws Exception {
        assertFalse(isAllowed(service, "https://api.football-data.org:8443/v4/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_refusesUnreadableTargetHost() throws Exception {
        assertFalse(isAllowed(service, "https://api_football-data.org/v4/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_refusesBaseWithoutScheme() throws Exception {
        FootballDataProxyService noScheme = newService("api.football-data.org/v4", "");
        assertFalse(isAllowed(noScheme, BASE + "/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_refusesBaseWithoutReadableHost() throws Exception {
        FootballDataProxyService noHost = newService("https://api_football-data.org/v4", "");
        assertFalse(isAllowed(noHost, BASE + "/competitions/FL1"));
    }

    @Test
    void isAllowedApiUri_refusesWhenBaseUrlIsNotAValidUri() throws Exception {
        FootballDataProxyService invalid = newService("http://[invalid", "");
        assertFalse(isAllowed(invalid, BASE + "/competitions/FL1"));
    }

    // ==================== En-têtes sortants ====================

    @Test
    void outgoingHeaders_onlyAcceptLanguageIsRelayed_andAcceptIsForcedToJson() {
        upstreamReturns(ok("{}"));
        HttpHeaders incoming = new HttpHeaders();
        incoming.set("Accept-Language", "fr-FR");
        incoming.set("Authorization", "Bearer secret");
        incoming.set("Cookie", "session=abc");
        incoming.set("X-Forwarded-For", "10.0.0.1");
        incoming.set("X-Auth-Token", "client-supplied-token");

        service.getCompetition("FL1", incoming);

        HttpHeaders sent = capturedRequest().getHeaders();
        assertEquals("fr-FR", sent.getFirst("Accept-Language"));
        assertEquals(java.util.List.of(MediaType.APPLICATION_JSON), sent.getAccept());
        assertNull(sent.getFirst("Authorization"));
        assertNull(sent.getFirst("Cookie"));
        assertNull(sent.getFirst("X-Forwarded-For"));
        // Le jeton du client est écrasé par celui du serveur.
        assertEquals("test-token", sent.getFirst("X-Auth-Token"));
        assertEquals(java.util.Set.of("Accept", "Accept-Language", "X-Auth-Token"), sent.headerNames());
    }

    @Test
    void outgoingHeaders_clientAcceptIsOverwrittenByApplicationJson() {
        upstreamReturns(ok("{}"));
        HttpHeaders incoming = new HttpHeaders();
        incoming.set("accept", "text/html");

        service.getCompetition("FL1", incoming);

        HttpHeaders sent = capturedRequest().getHeaders();
        assertEquals(java.util.List.of(MediaType.APPLICATION_JSON), sent.getAccept());
        assertEquals(1, sent.get("Accept").size());
    }

    @Test
    void outgoingHeaders_headerNamesAreMatchedCaseInsensitively() {
        upstreamReturns(ok("{}"));
        HttpHeaders incoming = new HttpHeaders();
        incoming.put("ACCEPT-LANGUAGE", java.util.List.of("en"));

        service.getCompetition("FL1", incoming);

        assertEquals("en", capturedRequest().getHeaders().getFirst("Accept-Language"));
    }

    @Test
    void outgoingHeaders_withoutIncomingHeaders_stillSendsAcceptAndToken() {
        upstreamReturns(ok("{}"));

        service.getCompetition("FL1", null);

        HttpHeaders sent = capturedRequest().getHeaders();
        assertEquals(java.util.List.of(MediaType.APPLICATION_JSON), sent.getAccept());
        assertEquals("test-token", sent.getFirst("X-Auth-Token"));
    }

    @Test
    void outgoingHeaders_noAuthTokenWhenTokenIsEmpty() {
        FootballDataProxyService noToken = newService(BASE, "");
        upstreamReturns(ok("{}"));

        noToken.getCompetition("FL1", new HttpHeaders());

        assertFalse(capturedRequest().getHeaders().containsHeader("X-Auth-Token"));
    }

    @Test
    void outgoingHeaders_noAuthTokenWhenTokenIsNull() {
        FootballDataProxyService noToken = newService(BASE, null);
        upstreamReturns(ok("{}"));

        noToken.getCompetition("FL1", new HttpHeaders());

        assertFalse(capturedRequest().getHeaders().containsHeader("X-Auth-Token"));
    }

    // ==================== Réponse amont réussie ====================

    @Test
    void success_anyUpstream2xxStatusGivesTheTypedBody() {
        upstreamReturns(new ResponseEntity<>("{\"id\":1}", new HttpHeaders(), HttpStatus.ACCEPTED));

        CompetitionResponse response = service.getCompetition("FL1", new HttpHeaders());

        assertEquals(1L, response.id());
    }

    @Test
    void success_upstreamContentTypeIsIgnored() {
        HttpHeaders upstream = new HttpHeaders();
        upstream.setContentType(MediaType.TEXT_PLAIN);
        upstreamReturns(new ResponseEntity<>("{\"id\":1,\"name\":\"Ligue 1\"}", upstream, HttpStatus.OK));

        CompetitionResponse response = service.getCompetition("FL1", new HttpHeaders());

        assertEquals("Ligue 1", response.name());
    }

    @Test
    void success_unknownFieldsAreIgnored() {
        upstreamReturns(ok("{\"id\":1,\"type\":\"LEAGUE\",\"area\":{\"name\":\"France\"}}"));

        CompetitionResponse response = service.getCompetition("FL1", new HttpHeaders());

        assertEquals(new CompetitionResponse(1L, null, null, null), response);
    }

    @Test
    void success_unreadableBody_isAnUpstreamFailureAndIsNotCached() {
        upstreamReturns(ok("ceci n'est pas du JSON"));

        assertThrows(UpstreamUnavailableException.class,
            () -> service.getCompetition("FL1", new HttpHeaders()));

        assertNull(cacheManager.getCache(CacheConfig.STATIC_CACHE).get("competition_FL1"));
    }

    @Test
    void success_emptyBody_isAnUpstreamFailureAndIsNotCached() {
        upstreamReturns(new ResponseEntity<>(null, new HttpHeaders(), HttpStatus.OK));

        assertThrows(UpstreamUnavailableException.class,
            () -> service.getCompetition("FL1", new HttpHeaders()));

        assertNull(cacheManager.getCache(CacheConfig.STATIC_CACHE).get("competition_FL1"));
    }

    // ==================== Erreurs amont ====================

    private UpstreamErrorException upstreamErrorFor(HttpHeaders upstreamHeaders, String body) {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", upstreamHeaders,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        return assertThrows(UpstreamErrorException.class,
            () -> service.getCompetition("FL1", new HttpHeaders()));
    }

    /*
     * Depuis #35, seul le statut amont est conservé : le corps de l'erreur de football-data.org
     * part dans les logs, le client reçoit un ProblemDetail construit par ApiExceptionHandler.
     */
    @Test
    void upstreamError_keepsTheUpstreamStatus() {
        UpstreamErrorException e = upstreamErrorFor(new HttpHeaders(), "{\"message\":\"nope\"}");

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void upstreamError_withoutHeadersKeepsTheUpstreamStatus() {
        UpstreamErrorException e = upstreamErrorFor(null, "{}");

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void upstreamError_messageDoesNotContainTheUpstreamBody() {
        UpstreamErrorException e = upstreamErrorFor(new HttpHeaders(), "jeton-secret-dans-le-corps");

        assertFalse(e.getMessage().contains("jeton-secret-dans-le-corps"));
    }

    // ==================== Cache de /matches ====================

    @Test
    void matches_goThroughTheStaticCache() {
        upstreamReturns(ok("{\"matches\":[]}"));

        MatchesResponse first = service.getMatches("FL1", new HttpHeaders());
        MatchesResponse second = service.getMatches("FL1", new HttpHeaders());

        assertSame(first, second);
        verify(restTemplate).exchange(eq(URI.create(BASE + "/competitions/FL1/matches")),
            eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        assertNotNull(cacheManager.getCache(CacheConfig.STATIC_CACHE).get("matches_FL1"));
        assertNull(cacheManager.getCache(CacheConfig.CURRENT_SEASON_CACHE).get("matches_FL1"));
        assertNull(cacheManager.getCache(CacheConfig.HISTORICAL_SEASON_CACHE).get("matches_FL1"));
    }

    @Test
    void matches_ofDifferentCompetitionsAreCachedSeparately() {
        upstreamReturns(ok("{\"matches\":[]}"));

        service.getMatches("FL1", new HttpHeaders());
        service.getMatches("PL", new HttpHeaders());

        verify(restTemplate).exchange(eq(URI.create(BASE + "/competitions/FL1/matches")),
            eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
        verify(restTemplate).exchange(eq(URI.create(BASE + "/competitions/PL/matches")),
            eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class));
    }

    @Test
    void whenACacheIsMissing_theCallStillWorksAndNothingIsStored() {
        FootballDataProxyService withoutCaches =
            new FootballDataProxyService(restTemplate, new NoCacheManager(), limiter(1000));
        setField(withoutCaches, "apiBaseUrl", BASE);
        setField(withoutCaches, "apiToken", "");
        upstreamReturns(ok("{}"));

        CompetitionResponse response = withoutCaches.getCompetition("FL1", new HttpHeaders());

        assertNotNull(response);
    }

    /** Gestionnaire de cache qui ne connaît aucun cache : getCache renvoie toujours null. */
    private static final class NoCacheManager implements CacheManager {
        @Override
        public org.springframework.cache.Cache getCache(String name) {
            return null;
        }

        @Override
        public java.util.Collection<String> getCacheNames() {
            return java.util.List.of();
        }
    }

    // ==================== Découpage des buteurs ====================

    private static final String THREE_SCORERS =
        "{\"count\":3,\"scorers\":[{\"goals\":3},{\"goals\":2},{\"goals\":1}]}";

    private ScorersResponse scorers(String upstreamBody, Integer limit) {
        upstreamReturns(ok(upstreamBody));
        return service.getScorers("FL1", limit, HISTORICAL_SEASON, new HttpHeaders());
    }

    private static java.util.List<Integer> goals(ScorersResponse response) {
        return response.scorers().stream().map(scorer -> scorer.goals()).toList();
    }

    @Test
    void scorers_invalidJsonBody_isAnUpstreamFailure() {
        upstreamReturns(ok("ceci n'est pas du JSON"));

        assertThrows(UpstreamUnavailableException.class,
            () -> service.getScorers("FL1", 5, HISTORICAL_SEASON, new HttpHeaders()));
    }

    @Test
    void scorers_bodyThatIsNotAnObject_isAnUpstreamFailure() {
        upstreamReturns(ok("[1,2,3]"));

        assertThrows(UpstreamUnavailableException.class,
            () -> service.getScorers("FL1", 1, HISTORICAL_SEASON, new HttpHeaders()));
    }

    @Test
    void scorers_whenScorersIsNotAnArray_isAnUpstreamFailure() {
        upstreamReturns(ok("{\"scorers\":\"oops\"}"));

        assertThrows(UpstreamUnavailableException.class,
            () -> service.getScorers("FL1", 1, HISTORICAL_SEASON, new HttpHeaders()));
    }

    @Test
    void scorers_withoutScorersField_givesAnEmptyList() {
        ScorersResponse response = scorers("{\"count\":3}", 1);

        assertEquals(java.util.List.of(), response.scorers());
        assertEquals(3, response.count());
    }

    @Test
    void scorers_withoutCountField_isTrimmedWithoutAddingCount() {
        ScorersResponse response = scorers("{\"scorers\":[{\"goals\":3},{\"goals\":2},{\"goals\":1}]}", 2);

        assertEquals(java.util.List.of(3, 2), goals(response));
        assertNull(response.count());
    }

    @Test
    void scorers_trimmedResponseUpdatesCount() {
        ScorersResponse response = scorers(THREE_SCORERS, 1);

        assertEquals(java.util.List.of(3), goals(response));
        assertEquals(1, response.count());
    }

    @Test
    void scorers_limitOfZeroOrLess_givesAnEmptyList() {
        assertEquals(java.util.List.of(), goals(scorers(THREE_SCORERS, 0)));
        assertEquals(java.util.List.of(), goals(service.getScorers("FL1", -1, HISTORICAL_SEASON, new HttpHeaders())));
    }

    @Test
    void scorers_limitAtOrAboveTheMaximum_returnsTheCachedResponseAsIs() {
        ScorersResponse first = scorers(THREE_SCORERS, 50);
        ScorersResponse second = service.getScorers("FL1", 50, HISTORICAL_SEASON, new HttpHeaders());

        assertSame(first, second);
        assertEquals(java.util.List.of(3, 2, 1), goals(first));
    }

    @Test
    void scorers_withoutLimit_returnsTheFullResponse() {
        ScorersResponse response = scorers(THREE_SCORERS, null);

        assertEquals(java.util.List.of(3, 2, 1), goals(response));
        assertEquals(3, response.count());
    }

    @Test
    void scorers_trimmingDoesNotAlterTheCachedResponse() {
        scorers(THREE_SCORERS, 1);

        ScorersResponse full = service.getScorers("FL1", null, HISTORICAL_SEASON, new HttpHeaders());

        assertEquals(java.util.List.of(3, 2, 1), goals(full));
        assertEquals(3, full.count());
    }

    @Test
    void scorers_upstreamError_isPropagated() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(),
                "{\"scorers\":[1,2,3]}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        UpstreamErrorException e = assertThrows(UpstreamErrorException.class,
            () -> service.getScorers("FL1", 1, HISTORICAL_SEASON, new HttpHeaders()));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }

    @Test
    void scorers_rateLimited_doesNotCallUpstream() {
        FootballDataProxyService limited = new FootballDataProxyService(
            restTemplate, cacheManager, limiter(0));
        setField(limited, "apiBaseUrl", BASE);
        setField(limited, "apiToken", "");

        assertThrows(RateLimitExceededException.class,
            () -> limited.getScorers("FL1", 1, null, new HttpHeaders()));
        verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }
}
