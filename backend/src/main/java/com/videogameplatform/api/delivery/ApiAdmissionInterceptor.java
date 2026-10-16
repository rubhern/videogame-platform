package com.videogameplatform.api.delivery;

import com.videogameplatform.api.generated.model.ErrorCategory;
import com.videogameplatform.api.generated.model.Problem;
import com.videogameplatform.api.generated.model.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.ObjectMapper;

/** Per-instance resource admission, never an account quota or an authorization decision. */
public final class ApiAdmissionInterceptor implements HandlerInterceptor {

    private static final String ADMISSION = ApiAdmissionInterceptor.class.getName() + ".admission";

    private final Semaphore requests;
    private final Semaphore writes;
    private final ObjectMapper mapper;

    ApiAdmissionInterceptor(int maxRequests, int maxWrites, ObjectMapper mapper) {
        if (maxRequests < 1 || maxWrites < 1 || maxWrites >= maxRequests) {
            throw new IllegalArgumentException("Require 0 < maxWrites < maxRequests");
        }
        this.requests = new Semaphore(maxRequests);
        this.writes = new Semaphore(maxWrites);
        this.mapper = mapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        boolean write =
                switch (request.getMethod()) {
                    case "GET", "HEAD", "OPTIONS" -> false;
                    default -> true;
                };
        if (write && !writes.tryAcquire()) {
            reject(request, response);
            return false;
        }
        if (!requests.tryAcquire()) {
            if (write) {
                writes.release();
            }
            reject(request, response);
            return false;
        }
        request.setAttribute(ADMISSION, write);
        return true;
    }

    @Override
    public void afterCompletion(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler,
            Exception exception) {
        Object write = request.getAttribute(ADMISSION);
        if (write != null) {
            request.removeAttribute(ADMISSION);
            requests.release();
            if (Boolean.TRUE.equals(write)) {
                writes.release();
            }
        }
    }

    private void reject(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String correlation = MDC.get("correlationId");
        if (correlation == null) {
            correlation = UUID.randomUUID().toString();
        }
        request.setAttribute(
                "com.videogameplatform.observability.error-code", "SERVICE_OVERLOADED");
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.RETRY_AFTER, "1");
        response.setHeader("X-Correlation-ID", correlation);
        mapper.writeValue(
                response.getOutputStream(),
                new Problem(
                                "urn:videogame-platform:problem:service-overloaded",
                                "Service is overloaded",
                                503,
                                "Retry after capacity is available; do not automatically replay commands.",
                                "urn:videogame-platform:problem-instance:" + correlation,
                                ProblemCode.SERVICE_OVERLOADED,
                                ErrorCategory.TECHNICAL,
                                correlation)
                        .violations(null));
    }
}
