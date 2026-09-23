package com.videogameplatform.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.ServletException;
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

    private final CorrelationIdFilter filter = new CorrelationIdFilter();
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
        assertThat(event.getFormattedMessage()).isEqualTo("HTTP request completed");
        assertThat(keyValue(event, "http.method")).isEqualTo("GET");
        assertThat(keyValue(event, "http.route")).isEqualTo(ROUTE_TEMPLATE);
        assertThat(keyValue(event, "http.status_code")).isEqualTo(500);
        assertThat(keyValue(event, "http.outcome")).isEqualTo("SERVER_ERROR");
        assertThat(keyValue(event, "duration_ms")).isInstanceOf(Long.class);
        assertThat(event.getKeyValuePairs().toString())
                .doesNotContain("private-user-123", "query-secret", "credential-secret");
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
