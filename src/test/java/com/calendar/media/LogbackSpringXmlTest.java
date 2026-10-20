package com.calendar.media;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import com.calendar.media.configuration.StorageClientConfig;
import com.calendar.media.exception.TechnicalErrorCode;
import com.calendar.media.exception.TechnicalException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Covers {@code logback-spring.xml}: a URL password never reaches the console, whether it sits in
 * the message or in a stack trace.
 *
 * <p>The leak is real, not hypothetical: on an error answer, WebClient puts the request URL,
 * userinfo included, in its exception message and checkpoint. The first assertion of each test
 * proves the secret is in the raw text before the layout masks it.
 */
class LogbackSpringXmlTest {

    private static final String SECRET = "s3cr3t";

    private OutputStreamAppender<ILoggingEvent> console;
    private Logger logger;

    @BeforeEach
    void configure() throws Exception {
        LoggerContext context = new LoggerContext();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        configurator.doConfigure(getClass().getResource("/logback-spring.xml"));
        logger = context.getLogger(Logger.ROOT_LOGGER_NAME);
        @SuppressWarnings("unchecked")
        OutputStreamAppender<ILoggingEvent> appender = (OutputStreamAppender<ILoggingEvent>) logger.getAppender("CONSOLE");
        console = appender;
    }

    private String format(String message, Throwable error) {
        LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, message, error, null);
        return new String(console.getEncoder().encode(event), StandardCharsets.UTF_8);
    }

    private static String trace(Throwable error) {
        StringWriter out = new StringWriter();
        error.printStackTrace(new PrintWriter(out));
        return out.toString();
    }

    /**
     * What the ERROR log of GlobalErrorHandler receives when this service's storage client is sent to
     * a URL carrying a password, and the other end answers an error.
     */
    private static TechnicalException storageErrorAnswerFromAUrlWithAPassword() {
        DisposableServer garage = HttpServer.create().host("127.0.0.1").port(0)
                .handle((request, response) -> response.status(401).sendString(Mono.just("<Error>denied</Error>")))
                .bindNow();
        try {
            Throwable failure = catchThrowable(() -> new StorageClientConfig().storageWebClient()
                    .get().uri("http://wely:" + SECRET + "@127.0.0.1:" + garage.port() + "/wely-avatars/avatars/u1.jpg")
                    .retrieve().toBodilessEntity()
                    .block(Duration.ofSeconds(10)));
            return new TechnicalException(TechnicalErrorCode.STORAGE_UNAVAILABLE, failure);
        } finally {
            garage.disposeNow();
        }
    }

    @Test
    void consolePattern_shouldMaskAUrlPassword_inTheStackTrace() {
        TechnicalException failure = storageErrorAnswerFromAUrlWithAPassword();
        assertThat(trace(failure)).contains(SECRET);

        String line = format("Technical failure MED-TEC-001 on /x (request 1)", failure);

        assertThat(line)
                .doesNotContain(SECRET)
                .contains("wely:****@127.0.0.1")
                .contains("WebClientResponseException");
    }

    @Test
    void consolePattern_shouldPrintTheStackTraceOnce() {
        String line = format("Technical failure", storageErrorAnswerFromAUrlWithAPassword());

        // Without %nopex, logback appends its own, unmasked copy of the trace.
        assertThat(line.split("Caused by: org.springframework.web.reactive.function.client.WebClientResponseException",
                -1)).hasSize(2);
    }

    @Test
    void consolePattern_shouldMaskAUrlPassword_inTheMessage() {
        String line = format("401 Unauthorized from PUT http://wely:" + SECRET
                + "@dev-wely-garage-service:3900/wely-avatars/avatars/u1.jpg", null);

        assertThat(line).doesNotContain(SECRET)
                .contains("http://wely:****@dev-wely-garage-service:3900/wely-avatars/avatars/u1.jpg");
    }

    @Test
    void consolePattern_shouldLeaveAUrlWithoutPasswordAlone() {
        String line = format("Request to PUT http://dev-wely-garage-service:3900/wely-avatars/avatars/u1.jpg", null);

        assertThat(line).contains("http://dev-wely-garage-service:3900/wely-avatars/avatars/u1.jpg");
    }
}
