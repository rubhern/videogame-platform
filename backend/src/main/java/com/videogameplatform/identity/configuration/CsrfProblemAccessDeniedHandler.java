package com.videogameplatform.identity.configuration;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Writes the reviewed RFC 9457 representation for failures rejected before MVC. */
@Component
public final class CsrfProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();

    public CsrfProblemAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception)
            throws IOException, ServletException {
        if (!(exception instanceof CsrfException)) {
            fallback.handle(request, response, exception);
            return;
        }

        String correlationId = correlationId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:videogame-platform:problem:csrf-validation-failed");
        body.put("title", "CSRF validation failed");
        body.put("status", HttpServletResponse.SC_FORBIDDEN);
        body.put("detail", "Obtain current CSRF material and explicitly retry.");
        body.put("instance", "urn:videogame-platform:problem-instance:" + correlationId);
        body.put("code", "CSRF_VALIDATION_FAILED");
        body.put("category", "authorization");
        body.put("correlationId", correlationId);

        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader("X-Correlation-ID", correlationId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static String correlationId() {
        String value = MDC.get("correlationId");
        return value == null ? UUID.randomUUID().toString() : value;
    }
}
