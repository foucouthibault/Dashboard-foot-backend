package fr.foucouthibault.dashboardfoot.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import fr.foucouthibault.dashboardfoot.config.CacheConfig;
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
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests de caractérisation du service : ils décrivent le comportement actuel (contrôle SSRF,
 * en-têtes relayés, en-têtes de réponse, découpage des buteurs) sans le juger, pour sécuriser
 * les refactos #34 et #35. Complètent {@link FootballDataProxyServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
class FootballDataProxyServiceCharacterizationTest {

    private static final String BASE = "https://api.football-data.org/v4";
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

    /** Limiteur réel sur une horloge figée : aucun test ne dépend de l'heure réelle. */
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

        ResponseEntity<String> response = misconfigured.getCompetition("FL1", new HttpHeaders());

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("{\"error\":\"Invalid upstream URL\"}", response.getBody());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
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

        misconfigured.getCompetition("FL1", new HttpHeaders());

        assertNull(cacheManager.getCache(CacheConfig.STATIC_CACHE).get("competition_FL1"));
    }

    @Test
    void ssrf_malformedBaseUrl_givesA502WithoutLeakingTheMessage() {
        // URI.create(url) échoue avant le contrôle SSRF : c'est le catch générique qui répond.
        FootballDataProxyService misconfigured = newService("http://[invalid", "test-token");

        ResponseEntity<String> response = misconfigured.getCompetition("FL1", new HttpHeaders());

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("{\"error\":\"Failed to fetch data from API\"}", response.getBody());
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

    // ==================== En-têtes de réponse ====================

    @Test
    void responseHeaders_transferEncodingAndContentEncodingAreFiltered() {
        HttpHeaders upstream = new HttpHeaders();
        upstream.setContentType(MediaType.APPLICATION_JSON);
        upstream.set("Transfer-Encoding", "chunked");
        upstream.set("content-encoding", "gzip");
        upstream.set("X-Requests-Available-Minute", "9");
        upstreamReturns(new ResponseEntity<>("{}", upstream, HttpStatus.OK));

        ResponseEntity<String> response = service.getCompetition("FL1", new HttpHeaders());

        assertFalse(response.getHeaders().containsHeader("Transfer-Encoding"));
        assertFalse(response.getHeaders().containsHeader("Content-Encoding"));
        assertEquals("9", response.getHeaders().getFirst("X-Requests-Available-Minute"));
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test
    void responseHeaders_jsonContentTypeIsAddedWhenMissing() {
        upstreamReturns(new ResponseEntity<>("{}", new HttpHeaders(), HttpStatus.OK));

        ResponseEntity<String> response = service.getCompetition("FL1", new HttpHeaders());

        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test
    void responseHeaders_existingContentTypeIsKept() {
        HttpHeaders upstream = new HttpHeaders();
        upstream.setContentType(MediaType.TEXT_PLAIN);
        upstreamReturns(new ResponseEntity<>("{}", upstream, HttpStatus.OK));

        ResponseEntity<String> response = service.getCompetition("FL1", new HttpHeaders());

        assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType());
    }

    @Test
    void responseHeaders_successStatusAndBodyAreRelayedUnchanged() {
        upstreamReturns(new ResponseEntity<>("{\"id\":1}", new HttpHeaders(), HttpStatus.ACCEPTED));

        ResponseEntity<String> response = service.getCompetition("FL1", new HttpHeaders());

        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("{\"id\":1}", response.getBody());
    }

    // ==================== Erreurs amont ====================

    @Test
    void upstreamError_withoutContentTypeDefaultsToJson() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", new HttpHeaders(),
                "{\"message\":\"nope\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        ResponseEntity<String> response = service.getCompetition("FL1", new HttpHeaders());

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
        assertEquals("{\"message\":\"nope\"}", response.getBody());
    }

    @Test
    void upstreamError_withoutHeadersDefaultsToJson() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null,
                "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        ResponseEntity<String> response = service.getCompetition("FL1", new HttpHeaders());

        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test
    void upstreamError_keepsTheUpstreamContentType() {
        HttpHeaders upstream = new HttpHeaders();
        upstream.setContentType(MediaType.TEXT_PLAIN);
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", upstream,
                "denied".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        ResponseEntity<String> response = service.getCompetition("FL1", new HttpHeaders());

        assertEquals(MediaType.TEXT_PLAIN, response.getHeaders().getContentType());
        assertEquals("denied", response.getBody());
    }

    // ==================== Cache de /matches ====================

    @Test
    void matches_goThroughTheStaticCache() {
        upstreamReturns(ok("{\"matches\":[]}"));

        ResponseEntity<String> first = service.getMatches("FL1", new HttpHeaders());
        ResponseEntity<String> second = service.getMatches("FL1", new HttpHeaders());

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

        ResponseEntity<String> response = withoutCaches.getCompetition("FL1", new HttpHeaders());

        assertEquals(HttpStatus.OK, response.getStatusCode());
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

    private ResponseEntity<String> scorers(String upstreamBody, Integer limit) {
        upstreamReturns(ok(upstreamBody));
        return service.getScorers("FL1", limit, "2023", new HttpHeaders());
    }

    @Test
    void scorers_invalidJsonBody_returnsTheFullResponseWithoutError() {
        ResponseEntity<String> response = scorers("ceci n'est pas du JSON", 5);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ceci n'est pas du JSON", response.getBody());
    }

    @Test
    void scorers_bodyThatIsNotAnObject_returnsTheFullResponse() {
        ResponseEntity<String> response = scorers("[1,2,3]", 1);

        assertEquals("[1,2,3]", response.getBody());
    }

    @Test
    void scorers_withoutScorersField_returnsTheFullResponse() {
        ResponseEntity<String> response = scorers("{\"count\":3}", 1);

        assertEquals("{\"count\":3}", response.getBody());
    }

    @Test
    void scorers_whenScorersIsNotAnArray_returnsTheFullResponse() {
        ResponseEntity<String> response = scorers("{\"scorers\":\"oops\"}", 1);

        assertEquals("{\"scorers\":\"oops\"}", response.getBody());
    }

    @Test
    void scorers_withoutCountField_isTrimmedWithoutAddingCount() {
        ResponseEntity<String> response = scorers("{\"scorers\":[{\"a\":1},{\"a\":2},{\"a\":3}]}", 2);

        assertEquals("{\"scorers\":[{\"a\":1},{\"a\":2}]}", response.getBody());
    }

    @Test
    void scorers_trimmedResponseDropsContentLengthButKeepsOtherHeaders() {
        HttpHeaders upstream = new HttpHeaders();
        upstream.setContentType(MediaType.APPLICATION_JSON);
        upstream.setContentLength(999);
        upstream.set("X-Requests-Available-Minute", "7");
        upstreamReturns(new ResponseEntity<>("{\"count\":3,\"scorers\":[1,2,3]}", upstream, HttpStatus.OK));

        ResponseEntity<String> response = service.getScorers("FL1", 1, "2023", new HttpHeaders());

        assertEquals("{\"count\":1,\"scorers\":[1]}", response.getBody());
        assertFalse(response.getHeaders().containsHeader("Content-Length"));
        assertEquals("7", response.getHeaders().getFirst("X-Requests-Available-Minute"));
        assertEquals(MediaType.APPLICATION_JSON, response.getHeaders().getContentType());
    }

    @Test
    void scorers_limitAtOrAboveTheMaximum_returnsTheCachedResponseAsIs() {
        ResponseEntity<String> response = scorers("{\"count\":3,\"scorers\":[1,2,3]}", 50);

        assertEquals("{\"count\":3,\"scorers\":[1,2,3]}", response.getBody());
    }

    @Test
    void scorers_withoutLimit_returnsTheFullResponse() {
        ResponseEntity<String> response = scorers("{\"count\":3,\"scorers\":[1,2,3]}", null);

        assertEquals("{\"count\":3,\"scorers\":[1,2,3]}", response.getBody());
    }

    @Test
    void scorers_upstreamError_isReturnedUntrimmed() {
        when(restTemplate.exchange(any(URI.class), eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(),
                "{\"scorers\":[1,2,3]}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));

        ResponseEntity<String> response = service.getScorers("FL1", 1, "2023", new HttpHeaders());

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertEquals("{\"scorers\":[1,2,3]}", response.getBody());
    }

    @Test
    void scorers_nullBody_isReturnedUntrimmed() {
        upstreamReturns(new ResponseEntity<>(null, new HttpHeaders(), HttpStatus.OK));

        ResponseEntity<String> response = service.getScorers("FL1", 1, "2023", new HttpHeaders());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void scorers_rateLimited_isReturnedUntrimmed() {
        FootballDataProxyService limited = new FootballDataProxyService(
            restTemplate, cacheManager, limiter(0));
        setField(limited, "apiBaseUrl", BASE);
        setField(limited, "apiToken", "");

        ResponseEntity<String> response = limited.getScorers("FL1", 1, null, new HttpHeaders());

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        verify(restTemplate, never()).exchange(any(URI.class), any(HttpMethod.class), any(HttpEntity.class), eq(String.class));
    }
}
