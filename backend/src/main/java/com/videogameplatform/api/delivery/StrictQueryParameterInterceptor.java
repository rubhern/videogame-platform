package com.videogameplatform.api.delivery;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Central enforcement of the API convention that query parameters are closed. */
@Component
final class StrictQueryParameterInterceptor implements HandlerInterceptor {

    private static final Map<String, ParameterPolicy> POLICIES =
            Map.of(
                    "/api/v1/releases",
                    new ParameterPolicy(
                            Set.of("view", "platformId", "regionId", "page", "pageSize"),
                            Set.of("page", "pageSize")));

    private final ReleaseApiMetrics metrics;

    StrictQueryParameterInterceptor(ReleaseApiMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.GET.matches(request.getMethod())) {
            return true;
        }
        ParameterPolicy policy = POLICIES.get(request.getRequestURI());
        if (policy == null) {
            return true;
        }

        String unknown =
                request.getParameterMap().keySet().stream()
                        .filter(name -> !policy.allowed().contains(name))
                        .sorted()
                        .findFirst()
                        .orElse(null);
        if (unknown != null) {
            ApiRequestException exception =
                    new ApiRequestException("REQUEST_PARAMETER_UNKNOWN", "/query/" + unknown);
            metrics.validationFailure(request.getParameter("view"), exception);
            throw exception;
        }
        for (String name : policy.allowed()) {
            String[] values = request.getParameterValues(name);
            if (values != null && values.length > 1) {
                String code =
                        policy.pagination().contains(name)
                                ? "PAGINATION_INVALID"
                                : "FILTER_INVALID";
                ApiRequestException exception = new ApiRequestException(code, "/query/" + name);
                metrics.validationFailure(request.getParameter("view"), exception);
                throw exception;
            }
        }
        return true;
    }

    private record ParameterPolicy(Set<String> allowed, Set<String> pagination) {}
}
