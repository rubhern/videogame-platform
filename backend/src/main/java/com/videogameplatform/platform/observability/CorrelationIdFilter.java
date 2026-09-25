package com.videogameplatform.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Adds a safe request correlation identifier and emits one allowlisted completion event.
 *
 * <p>The message repeats the bounded fields so a plain console line is as diagnostic as the
 * structured one; both come from the same values and never from the raw URL or query.
 */
@Component
@Order(-101)
final class CorrelationIdFilter extends OncePerRequestFilter {

    static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    static final String CORRELATION_ID_NAME = "correlationId";

    /**
     * Request attribute through which a delivery or security boundary reports the stable code of
     * the failure it answered. Producers in other modules use the same literal name.
     */
    static final String ERROR_CODE_ATTRIBUTE = "com.videogameplatform.observability.error-code";

    static final String UNMATCHED_ROUTE = "UNMATCHED";

    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final Pattern SAFE_CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern SAFE_ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

    private final RouteTemplateResolver unmatchedRoutes;

    @Autowired
    CorrelationIdFilter(
            @Qualifier("requestMappingHandlerMapping")
                    ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        this(new HandlerMappingRouteTemplateResolver(handlerMappings));
    }

    CorrelationIdFilter(RouteTemplateResolver unmatchedRoutes) {
        this.unmatchedRoutes = unmatchedRoutes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestPath = request.getRequestURI().substring(request.getContextPath().length());
        return "/actuator/health/liveness".equals(requestPath)
                || "/actuator/health/readiness".equals(requestPath);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = correlationId(request.getHeader(CORRELATION_ID_HEADER));
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        long startedAt = System.nanoTime();
        String previousCorrelationId = MDC.get(CORRELATION_ID_NAME);
        MDC.put(CORRELATION_ID_NAME, correlationId);
        Throwable failure = null;

        try {
            filterChain.doFilter(request, response);
        } catch (Exception exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                logCompletion(request, response, failure, startedAt);
            } finally {
                restoreCorrelationId(previousCorrelationId);
            }
        }
    }

    private void logCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Throwable failure,
            long startedAt) {
        int status =
                failure == null
                        ? response.getStatus()
                        : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
        String method = request.getMethod();
        String route = routeTemplate(request);
        String outcome = outcome(status);
        Optional<String> errorCode = errorCode(request);
        // Expected client errors stay at INFO; only server failures are operational warnings. The
        // cause of a technical failure is logged once, at ERROR, by the boundary that handled it.
        LoggingEventBuilder event =
                LOGGER.atLevel(status >= 500 ? Level.WARN : Level.INFO)
                        .addKeyValue("http.method", method)
                        .addKeyValue("http.route", route)
                        .addKeyValue("http.status_code", status)
                        .addKeyValue("http.outcome", outcome)
                        .addKeyValue("duration_ms", durationMillis);
        errorCode.ifPresent(code -> event.addKeyValue("error.code", code));
        event.log(
                "HTTP request completed: {} {} status={} outcome={}{} duration_ms={}",
                method,
                route,
                status,
                outcome,
                errorCode.map(code -> " code=" + code).orElse(""),
                durationMillis);
    }

    private static String correlationId(String candidate) {
        if (candidate != null && SAFE_CORRELATION_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    private static void restoreCorrelationId(String previousCorrelationId) {
        if (previousCorrelationId == null) {
            MDC.remove(CORRELATION_ID_NAME);
        } else {
            MDC.put(CORRELATION_ID_NAME, previousCorrelationId);
        }
    }

    private String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        if (pattern instanceof String value) {
            return value;
        }
        // A security boundary may answer before MVC; resolve the registered template, never the
        // raw path, so a rejected personal request still names its route.
        return unmatchedRoutes.resolve(request).orElse(UNMATCHED_ROUTE);
    }

    private static Optional<String> errorCode(HttpServletRequest request) {
        return request.getAttribute(ERROR_CODE_ATTRIBUTE) instanceof String code
                        && SAFE_ERROR_CODE.matcher(code).matches()
                ? Optional.of(code)
                : Optional.empty();
    }

    private static String outcome(int status) {
        return switch (status / 100) {
            case 1 -> "INFORMATIONAL";
            case 2 -> "SUCCESS";
            case 3 -> "REDIRECTION";
            case 4 -> "CLIENT_ERROR";
            case 5 -> "SERVER_ERROR";
            default -> "UNKNOWN";
        };
    }
}
