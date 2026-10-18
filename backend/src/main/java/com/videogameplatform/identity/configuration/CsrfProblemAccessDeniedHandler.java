package com.videogameplatform.identity.configuration;

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
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes the reviewed RFC 9457 representation for failures rejected before MVC: missing CSRF proof,
 * or an authenticated principal without the authority an operation requires.
 */
@Component
public final class CsrfProblemAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public CsrfProblemAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }
        boolean csrf = exception instanceof CsrfException;
        String code = csrf ? "CSRF_VALIDATION_FAILED" : "ACCESS_DENIED";
        String correlationId = correlationId();
        request.setAttribute(AuthenticationProblemEntryPoint.ERROR_CODE_ATTRIBUTE, code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(
                "type",
                csrf
                        ? "urn:videogame-platform:problem:csrf-validation-failed"
                        : "urn:videogame-platform:problem:access-denied");
        body.put("title", csrf ? "CSRF validation failed" : "Access is denied");
        body.put("status", HttpServletResponse.SC_FORBIDDEN);
        body.put(
                "detail",
                csrf
                        ? "Obtain current CSRF material and explicitly retry."
                        : "This session is not allowed to use this resource.");
        body.put("instance", "urn:videogame-platform:problem-instance:" + correlationId);
        body.put("code", code);
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
