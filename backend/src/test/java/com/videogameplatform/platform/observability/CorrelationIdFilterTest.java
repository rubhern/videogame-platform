package com.videogameplatform.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

class CorrelationIdFilterTest {

    private static final String ROUTE_TEMPLATE = "/games/{gameId}";

    private final CorrelationIdFilter filter = new CorrelationIdFilter(request -> Optional.empty());
    private final Logger logger = (Logger) LoggerFactory.getLogger(CorrelationIdFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureAccessLogs() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void restoreLoggingContext() {
        logger.detachAppender(appender);
        appender.stop();
        MDC.clear();
    }

    @Test
    void countsOnlyClosedCodesAndSeparatesTheExactExpectedRatingAbsence() throws Exception {
        var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        var metered = new CorrelationIdFilter(request -> Optional.empty(), registry);
        for (String method : new String[] {"GET", "PUT", "DELETE"}) {
            var request = new MockHttpServletRequest(method, "/api/v1/me/ratings/private-game");
            request.setAttribute(
                    HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/me/ratings/{gameId}");
            metered.doFilter(
                    request,
                    new MockHttpServletResponse(),
                    (req, res) -> {
                        req.setAttribute(
                                CorrelationIdFilter.ERROR_CODE_ATTRIBUTE, "RATING_NOT_FOUND");
                        ((MockHttpServletResponse) res).setStatus(404);
                    });
        }
        var request = new MockHttpServletRequest("GET", "/api/v1/games/private-game");
        metered.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> {
                    req.setAttribute(
                            CorrelationIdFilter.ERROR_CODE_ATTRIBUTE, "UNBOUNDED_ATTACKER_CODE");
                    ((MockHttpServletResponse) res).setStatus(503);
                });
        assertThat(
                        registry.get("platform.http.errors")
                                .tags("code", "RATING_NOT_FOUND", "kind", "expected_absence")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(
                        registry.get("platform.http.errors")
                                .tags("code", "RATING_NOT_FOUND", "kind", "client_rejection")
                                .counter()
                                .count())
                .isEqualTo(2);
        assertThat(
                        registry.get("platform.http.errors")
                                .tags("code", "UNCLASSIFIED", "kind", "server_failure")
                                .counter()
                                .count())
                .isEqualTo(1);
        assertThat(registry.getMeters())
                .allSatisfy(meter -> assertThat(meter.getId().getTags()).hasSize(2));
    }

    @Test
    void metricSinkFailureDoesNotChangeTheHttpResponse() throws Exception {
        var registry = org.mockito.Mockito.mock(io.micrometer.core.instrument.MeterRegistry.class);
        org.mockito.Mockito.when(
                        registry.counter(
                                org.mockito.ArgumentMatchers.anyString(),
                                org.mockito.ArgumentMatchers.<String[]>any()))
                .thenThrow(new IllegalStateException("private-sink-detail"));
        var metered = new CorrelationIdFilter(request -> Optional.empty(), registry);
        var response = new MockHttpServletResponse();
        metered.doFilter(
                new MockHttpServletRequest("GET", "/api/v1/games"),
                response,
                (req, res) -> ((MockHttpServletResponse) res).setStatus(503));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(appender.list)
                .allSatisfy(
                        event ->
                                assertThat(event.getFormattedMessage())
                                        .doesNotContain("private-sink-detail"));
    }

    @Test
    void preservesAValidCorrelationIdAndRestoresThePreviousMdcValue() throws Exception {
        String correlationId = "valid-correlation_23";
        var request = request(correlationId);
        var response = new MockHttpServletResponse();
        MDC.put(CorrelationIdFilter.CORRELATION_ID_NAME, "outer-correlation");

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) ->
                        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_NAME))
                                .isEqualTo(correlationId));

        assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER))
                .isEqualTo(correlationId);
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_NAME)).isEqualTo("outer-correlation");
        assertThat(singleAccessEvent().getMDCPropertyMap())
                .containsEntry(CorrelationIdFilter.CORRELATION_ID_NAME, correlationId);
    }

    @Test
    void replacesAnUnsafeCorrelationIdAndClearsTheMdcAfterTheRequest() throws Exception {
        var request = request("unsafe correlation/value");
        var response = new MockHttpServletResponse();

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> {
                    String effectiveCorrelationId =
                            response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
                    assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_NAME))
                            .isEqualTo(effectiveCorrelationId);
                });

        String effectiveCorrelationId =
                response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER);
        assertThat(effectiveCorrelationId).isNotEqualTo("unsafe correlation/value");
        assertThat(UUID.fromString(effectiveCorrelationId)).isNotNull();
        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_NAME)).isNull();
        assertThat(singleAccessEvent().getMDCPropertyMap())
                .containsEntry(CorrelationIdFilter.CORRELATION_ID_NAME, effectiveCorrelationId);
    }

    @Test
    void logsSafeRouteFieldsAndRestoresMdcWhenRequestProcessingThrows() {
        var request = request("exception-correlation");
        request.setRequestURI("/games/private-user-123");
        request.setQueryString("token=query-secret");
        var response = new MockHttpServletResponse();

        assertThatThrownBy(
                        () ->
                                filter.doFilter(
                                        request,
                                        response,
                                        (servletRequest, servletResponse) -> {
                                            throw new ServletException("credential-secret");
                                        }))
                .isInstanceOf(ServletException.class)
                .hasMessage("credential-secret");

        assertThat(MDC.get(CorrelationIdFilter.CORRELATION_ID_NAME)).isNull();
        var event = singleAccessEvent();
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage())
                .contains("GET", ROUTE_TEMPLATE, "500", "SERVER_ERROR")
                .doesNotContain("private-user-123", "query-secret", "credential-secret");
        assertThat(keyValue(event, "http.method")).isEqualTo("GET");
        assertThat(keyValue(event, "http.route")).isEqualTo(ROUTE_TEMPLATE);
        assertThat(keyValue(event, "http.status_code")).isEqualTo(500);
        assertThat(keyValue(event, "http.outcome")).isEqualTo("SERVER_ERROR");
        assertThat(keyValue(event, "duration_ms")).isInstanceOf(Long.class);
        assertThat(event.getKeyValuePairs().toString())
                .doesNotContain("private-user-123", "query-secret", "credential-secret");
    }

    @Test
    void rendersSuccessAndFailuresAsDistinguishableLinesWithTheReportedStableCode()
            throws Exception {
        filter.doFilter(request("success"), new MockHttpServletResponse(), (req, res) -> {});
        filter.doFilter(
                request("unauthenticated"),
                new MockHttpServletResponse(),
                (req, res) -> {
                    req.setAttribute(
                            CorrelationIdFilter.ERROR_CODE_ATTRIBUTE, "AUTHENTICATION_REQUIRED");
                    ((MockHttpServletResponse) res).setStatus(401);
                });
        filter.doFilter(
                request("unavailable"),
                new MockHttpServletResponse(),
                (req, res) -> {
                    req.setAttribute(
                            CorrelationIdFilter.ERROR_CODE_ATTRIBUTE, "CATALOGUE_READ_FAILED");
                    ((MockHttpServletResponse) res).setStatus(503);
                });

        assertThat(appender.list).hasSize(3);
        var success = appender.list.get(0);
        var unauthenticated = appender.list.get(1);
        var unavailable = appender.list.get(2);

        assertThat(success.getLevel()).isEqualTo(Level.INFO);
        assertThat(keyValue(success, "http.status_code")).isEqualTo(200);
        assertThat(keyValue(success, "http.outcome")).isEqualTo("SUCCESS");
        assertThat(keyValue(success, "error.code")).isNull();

        assertThat(unauthenticated.getLevel()).isEqualTo(Level.INFO);
        assertThat(keyValue(unauthenticated, "http.status_code")).isEqualTo(401);
        assertThat(keyValue(unauthenticated, "http.outcome")).isEqualTo("CLIENT_ERROR");
        assertThat(keyValue(unauthenticated, "error.code")).isEqualTo("AUTHENTICATION_REQUIRED");

        assertThat(unavailable.getLevel()).isEqualTo(Level.WARN);
        assertThat(keyValue(unavailable, "http.outcome")).isEqualTo("SERVER_ERROR");
        assertThat(keyValue(unavailable, "error.code")).isEqualTo("CATALOGUE_READ_FAILED");

        // The plain console renders only the message, so it must carry the same facts.
        assertThat(success.getFormattedMessage()).contains(ROUTE_TEMPLATE, "200", "SUCCESS");
        assertThat(unauthenticated.getFormattedMessage())
                .contains(ROUTE_TEMPLATE, "401", "AUTHENTICATION_REQUIRED");
        assertThat(unavailable.getFormattedMessage()).contains("503", "CATALOGUE_READ_FAILED");
        assertThat(success.getFormattedMessage())
                .isNotEqualTo(unauthenticated.getFormattedMessage());
    }

    @Test
    void ignoresAnUnboundedOrMalformedReportedCode() throws Exception {
        filter.doFilter(
                request("unsafe-code"),
                new MockHttpServletResponse(),
                (req, res) ->
                        req.setAttribute(CorrelationIdFilter.ERROR_CODE_ATTRIBUTE, "raw text"));

        assertThat(keyValue(singleAccessEvent(), "error.code")).isNull();
        assertThat(singleAccessEvent().getFormattedMessage()).doesNotContain("raw text");
    }

    @Test
    void resolvesARegisteredTemplateForARequestRejectedBeforeMvcAndNeverTheRawPath()
            throws Exception {
        var resolving =
                new CorrelationIdFilter(request -> Optional.of("/api/v1/me/ratings/{gameId}"));
        var unresolved = new CorrelationIdFilter(request -> Optional.empty());
        var rejected = new MockHttpServletRequest("PUT", "/api/v1/me/ratings/private-game-7");
        rejected.setQueryString("secret=query-secret");
        var unknown = new MockHttpServletRequest("GET", "/private/unknown-9");

        resolving.doFilter(rejected, new MockHttpServletResponse(), (req, res) -> {});
        unresolved.doFilter(unknown, new MockHttpServletResponse(), (req, res) -> {});

        assertThat(appender.list).hasSize(2);
        assertThat(keyValue(appender.list.get(0), "http.route"))
                .isEqualTo("/api/v1/me/ratings/{gameId}");
        assertThat(keyValue(appender.list.get(1), "http.route")).isEqualTo("UNMATCHED");
        assertThat(appender.list)
                .allSatisfy(
                        event ->
                                assertThat(event.getFormattedMessage() + event.getKeyValuePairs())
                                        .doesNotContain(
                                                "private-game-7", "query-secret", "unknown-9"));
    }

    @Test
    void skipsRoutineLivenessAndReadinessProbeLogging() throws Exception {
        for (String path :
                new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            var request = new MockHttpServletRequest("GET", path);
            var response = new MockHttpServletResponse();

            filter.doFilter(request, response, (servletRequest, servletResponse) -> {});

            assertThat(response.getHeader(CorrelationIdFilter.CORRELATION_ID_HEADER)).isNull();
        }

        assertThat(appender.list).isEmpty();
    }

    private static MockHttpServletRequest request(String correlationId) {
        var request = new MockHttpServletRequest("GET", "/games/123");
        request.addHeader(CorrelationIdFilter.CORRELATION_ID_HEADER, correlationId);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, ROUTE_TEMPLATE);
        return request;
    }

    private ILoggingEvent singleAccessEvent() {
        assertThat(appender.list).hasSize(1);
        return appender.list.getFirst();
    }

    private static Object keyValue(ILoggingEvent event, String key) {
        return event.getKeyValuePairs().stream()
                .filter(pair -> key.equals(pair.key))
                .map(pair -> pair.value)
                .findFirst()
                .orElse(null);
    }
}
