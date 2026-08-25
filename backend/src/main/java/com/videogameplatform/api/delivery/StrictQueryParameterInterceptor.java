package com.videogameplatform.api.delivery;

import com.videogameplatform.api.generated.ReleasesApi;
import com.videogameplatform.api.generated.model.ProblemCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Min;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Central enforcement of the API convention that query parameters are closed.
 */
@Component
final class StrictQueryParameterInterceptor implements HandlerInterceptor {
    private final ReleaseApiMetrics metrics;

    StrictQueryParameterInterceptor(ReleaseApiMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.GET.matches(request.getMethod())
                || !(handler instanceof HandlerMethod method)
                || !ReleasesApi.class.isAssignableFrom(method.getBeanType())) {
            return true;
        }
        Set<QueryParameter> parameters = queryParameters(method);
        Set<String> allowed =
                parameters.stream()
                        .map(QueryParameter::name)
                        .collect(Collectors.toUnmodifiableSet());

        String unknown =
                request.getParameterMap().keySet().stream()
                        .filter(name -> !allowed.contains(name))
                        .sorted()
                        .findFirst()
                        .orElse(null);
        if (unknown != null) {
            ApiRequestException exception =
                    new ApiRequestException(
                            ProblemCode.REQUEST_PARAMETER_UNKNOWN, "/query/" + unknown);
            metrics.validationFailure(request.getParameter("view"), exception);
            throw exception;
        }
        for (QueryParameter parameter : parameters) {
            String[] values = request.getParameterValues(parameter.name());
            if (values != null && values.length > 1) {
                ProblemCode code =
                        parameter.pagination()
                                ? ProblemCode.PAGINATION_INVALID
                                : ProblemCode.FILTER_INVALID;
                ApiRequestException exception =
                        new ApiRequestException(code, "/query/" + parameter.name());
                metrics.validationFailure(request.getParameter("view"), exception);
                throw exception;
            }
            if (values != null
                    && !parameter.acceptedValues().isEmpty()
                    && !parameter.acceptedValues().contains(values[0])) {
                ApiRequestException exception =
                        new ApiRequestException(
                                ProblemCode.FILTER_INVALID, "/query/" + parameter.name());
                metrics.validationFailure(request.getParameter("view"), exception);
                throw exception;
            }
        }
        return true;
    }

    private static Set<QueryParameter> queryParameters(HandlerMethod method) {
        return Arrays.stream(method.getMethodParameters())
                .map(StrictQueryParameterInterceptor::queryParameter)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static QueryParameter queryParameter(MethodParameter parameter) {
        RequestParam annotation = parameter.getParameterAnnotation(RequestParam.class);
        if (annotation == null) {
            return null;
        }
        String name = annotation.name().isBlank() ? annotation.value() : annotation.name();
        return new QueryParameter(
                name,
                parameter.hasParameterAnnotation(Min.class),
                acceptedValues(parameter.getParameterType()));
    }

    private static Set<String> acceptedValues(Class<?> parameterType) {
        if (!parameterType.isEnum()) {
            return Set.of();
        }
        return Arrays.stream(parameterType.getEnumConstants())
                .map(Object::toString)
                .collect(Collectors.toUnmodifiableSet());
    }

    private record QueryParameter(String name, boolean pagination, Set<String> acceptedValues) {}
}
