package fr.foucouthibault.dashboardfoot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import fr.foucouthibault.dashboardfoot.config.RequestIdFilter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Logs de production ({@code LOG_FORMAT=logstash}, voir render.yaml) : chaque ligne écrite
 * pendant une requête est un objet JSON qui porte son {@code requestId}, et rien de secret ni
 * de personnel n'y figure. {@code RestTemplate} est simulé : aucun appel à football-data.org.
 *
 * Spring Boot n'initialise la journalisation qu'une fois par JVM de test : la console garde le
 * format du premier contexte démarré. Le test vérifie donc que {@code LOG_FORMAT} est bien relié
 * à {@code logging.structured.format.console}, puis branche sur le logger racine l'encodeur
 * structuré de Spring Boot réglé sur ce format, le temps de la requête.
 */
@SpringBootTest(properties = {
    "LOG_FORMAT=logstash",
    "football-data.api.token=" + StructuredLoggingTest.TOKEN,
})
@AutoConfigureMockMvc
@DirtiesContext
class StructuredLoggingTest {

    static final String TOKEN = "cle-secrete-de-test";
    private static final String CLIENT_IP = "203.0.113.7";
    private static final String FORWARDED_IP = "198.51.100.9";
    private static final String REQUEST_ID = "requete-de-test-42";

    private final ObjectMapper json = JsonMapper.builder().build();
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private OutputStreamAppender<ILoggingEvent> jsonAppender;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Environment environment;

    @MockitoBean
    private RestTemplate restTemplate;

    @BeforeEach
    void attachStructuredAppender() {
        LoggerContext encoderContext = new LoggerContext();
        encoderContext.putObject(Environment.class.getName(), environment);

        StructuredLogEncoder encoder = new StructuredLogEncoder();
        encoder.setContext(encoderContext);
        encoder.setFormat(environment.getProperty("logging.structured.format.console"));
        encoder.setCharset(StandardCharsets.UTF_8);
        encoder.start();

        jsonAppender = new OutputStreamAppender<>();
        jsonAppender.setContext(encoderContext);
        jsonAppender.setEncoder(encoder);
        jsonAppender.setOutputStream(captured);
        jsonAppender.start();
        root.addAppender(jsonAppender);
    }

    @AfterEach
    void detachStructuredAppender() {
        root.detachAppender(jsonAppender);
        jsonAppender.stop();
    }

    @Test
    void logFormatVariableSelectsTheStructuredFormat() {
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("logstash");
    }

    @Test
    void everyRequestLineIsJsonWithTheRequestIdAndWithoutSecretsOrIp() throws Exception {
        when(restTemplate.exchange(eq(URI.create("https://api.football-data.org/v4/competitions/FL1")),
                eq(HttpMethod.GET), any(HttpEntity.class), eq(String.class)))
            .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", new HttpHeaders(), null, null));

        mockMvc.perform(get("/api/competitions/FL1")
                .header(RequestIdFilter.HEADER, REQUEST_ID)
                .header("X-Forwarded-For", FORWARDED_IP)
                .with(request -> {
                    request.setRemoteAddr(CLIENT_IP);
                    return request;
                }))
            .andExpect(status().isForbidden())
            .andExpect(header().string(RequestIdFilter.HEADER, REQUEST_ID));

        String output = captured.toString(StandardCharsets.UTF_8);
        List<JsonNode> entries = output.lines().filter(line -> !line.isBlank()).map(json::readTree).toList();
        assertThat(entries).isNotEmpty()
            .allSatisfy(entry -> assertThat(entry.path("requestId").asString()).isEqualTo(REQUEST_ID));

        JsonNode upstreamError = entries.stream()
            .filter(entry -> "amont_erreur".equals(entry.path("event").asString()))
            .findFirst()
            .orElseThrow();
        assertThat(upstreamError.path("upstreamStatus").asInt()).isEqualTo(403);
        assertThat(upstreamError.path("message").asString()).contains("football-data.org a répondu 403");

        assertThat(output)
            .doesNotContain(TOKEN)
            .doesNotContain(CLIENT_IP)
            .doesNotContain(FORWARDED_IP);
    }
}
