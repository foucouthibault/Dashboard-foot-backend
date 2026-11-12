package fr.foucouthibault.dashboardfoot.config;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Identifiant de requête : repris s'il est sûr, généré sinon, dans le MDC le temps de la requête. */
class RequestIdFilterTest {

    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

    private final RequestIdFilter filter = new RequestIdFilter();

    @Test
    void generatesAnIdPutInTheMdcAndTheResponseThenClearsTheMdc() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/competitions/FL1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenInChain.set(MDC.get(RequestIdFilter.MDC_KEY)));

        assertThat(seenInChain.get()).matches(UUID_PATTERN);
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo(seenInChain.get());
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void mdcIsClearedEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/competitions/FL1");

        try {
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
                throw new IllegalStateException("échec");
            });
        } catch (Exception expected) {
            // l'exception remonte telle quelle
        }

        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void reusesAShortAndSafeIncomingId() {
        assertThat(RequestIdFilter.requestIdFor("abc-123_x.y")).isEqualTo("abc-123_x.y");
        assertThat(RequestIdFilter.requestIdFor("a".repeat(64))).isEqualTo("a".repeat(64));
    }

    /** Un identifiant fourni par le client ne doit ni forger de ligne de log ni gonfler les logs. */
    @Test
    void replacesAnUnsafeOrTooLongIncomingId() {
        assertThat(RequestIdFilter.requestIdFor("abc\nERROR FAUSSE LIGNE")).matches(UUID_PATTERN);
        assertThat(RequestIdFilter.requestIdFor("{\"injection\":1}")).matches(UUID_PATTERN);
        assertThat(RequestIdFilter.requestIdFor("a".repeat(65))).matches(UUID_PATTERN);
        assertThat(RequestIdFilter.requestIdFor("")).matches(UUID_PATTERN);
        assertThat(RequestIdFilter.requestIdFor(null)).matches(UUID_PATTERN);
    }
}
