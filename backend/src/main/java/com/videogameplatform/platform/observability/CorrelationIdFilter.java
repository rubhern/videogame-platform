package com.videogameplatform.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Adds a safe request correlation identifier and emits one allowlisted access log. */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
final class CorrelationIdFilter extends OncePerRequestFilter {

    static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    static final String CORRELATION_ID_NAME = "correlationId";

    private static final Logger LOGGER = LoggerFactory.getLogger(CorrelationIdFilter.class);
    private static final Pattern SAFE_CORRELATION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

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
        } catch (ServletException | IOException | RuntimeException | Error exception) {
            failure = exception;
            throw exception;
        } finally {
            try {
                logAccess(request, response, failure, startedAt);
            } finally {
                restoreCorrelationId(previousCorrelationId);
            }
        }
    }

    private static void logAccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Throwable failure,
            long startedAt) {
        int status =
                failure == null
                        ? response.getStatus()
                        : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        long durationMillis = (System.nanoTime() - startedAt) / 1_000_000;
        LOGGER.atInfo()
                .addKeyValue("http.method", request.getMethod())
                .addKeyValue("http.route", routeTemplate(request))
                .addKeyValue("http.status_code", status)
                .addKeyValue("http.outcome", outcome(status))
                .addKeyValue("duration_ms", durationMillis)
                .log("HTTP request completed");
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

    private static String routeTemplate(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern instanceof String value ? value : "UNMATCHED";
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
