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
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Returns the contract Problem instead of redirecting unauthenticated API fetches. */
@Component
public final class AuthenticationProblemEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper objectMapper;

    public AuthenticationProblemEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception)
            throws IOException {
        String correlationId = correlationId();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "urn:videogame-platform:problem:authentication-required");
        body.put("title", "Authentication is required");
        body.put("status", HttpServletResponse.SC_UNAUTHORIZED);
        body.put("detail", "Establish a session before accessing this resource.");
        body.put("instance", "urn:videogame-platform:problem-instance:" + correlationId);
        body.put("code", "AUTHENTICATION_REQUIRED");
        body.put("category", "authentication");
        body.put("correlationId", correlationId);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
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
