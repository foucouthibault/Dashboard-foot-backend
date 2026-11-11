package fr.foucouthibault.dashboardfoot.config;

import fr.foucouthibault.dashboardfoot.exception.InvalidRequestException;
import fr.foucouthibault.dashboardfoot.exception.RateLimitExceededException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamErrorException;
import fr.foucouthibault.dashboardfoot.exception.UpstreamUnavailableException;
import io.sentry.Hint;
import io.sentry.SentryEvent;
import io.sentry.protocol.Request;
import io.sentry.protocol.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SentryEventFilterTest {

    private final SentryEventFilter filter = new SentryEventFilter();

    private SentryEvent filtered(Throwable throwable) {
        return filter.execute(new SentryEvent(throwable), new Hint());
    }

    @Test
    void internalRateLimitIsDropped() {
        assertThat(filtered(new RateLimitExceededException(10))).isNull();
    }

    @Test
    void invalidRequestIsDropped() {
        assertThat(filtered(new InvalidRequestException("saison invalide"))).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 403, 404, 500})
    void relayedStatusOtherThan429IsDropped(int status) {
        assertThat(filtered(new UpstreamErrorException(HttpStatus.valueOf(status)))).isNull();
    }

    @Test
    void relayed429IsKept() {
        assertThat(filtered(new UpstreamErrorException(HttpStatus.TOO_MANY_REQUESTS))).isNotNull();
    }

    @Test
    void upstreamUnavailableAndUnexpectedErrorsAreKept() {
        assertThat(filtered(new UpstreamUnavailableException(null))).isNotNull();
        assertThat(filtered(new IllegalStateException("bug"))).isNotNull();
    }

    @Test
    void requestHeadersCookiesEnvironmentAndUserAreRemoved() {
        SentryEvent event = new SentryEvent(new IllegalStateException("bug"));
        Request request = new Request();
        request.setUrl("https://dashboard-foot-backend.onrender.com/api/competitions/FL1");
        request.setHeaders(Map.of("X-Forwarded-For", "203.0.113.7"));
        request.setCookies("session=secret");
        request.setEnvs(Map.of("REMOTE_ADDR", "203.0.113.7"));
        event.setRequest(request);
        User user = new User();
        user.setIpAddress("203.0.113.7");
        event.setUser(user);

        SentryEvent result = filter.execute(event, new Hint());

        assertThat(result.getRequest().getUrl()).endsWith("/api/competitions/FL1");
        assertThat(result.getRequest().getHeaders()).isNull();
        assertThat(result.getRequest().getCookies()).isNull();
        assertThat(result.getRequest().getEnvs()).isNull();
        assertThat(result.getUser()).isNull();
    }
}
