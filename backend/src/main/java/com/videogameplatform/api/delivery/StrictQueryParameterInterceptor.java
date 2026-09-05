package com.videogameplatform.api.delivery;

import com.videogameplatform.api.generated.CatalogueApi;
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

    private static final String SEARCH_PARAMETER = "q";

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!HttpMethod.GET.matches(request.getMethod())
                || !(handler instanceof HandlerMethod method)
                || !isClosedQueryOperation(method)) {
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
            throw exception;
        }
        for (QueryParameter parameter : parameters) {
            String[] values = request.getParameterValues(parameter.name());
            if (values != null && values.length > 1) {
                ApiRequestException exception =
                        new ApiRequestException(
                                repeatedParameterCode(parameter), "/query/" + parameter.name());
                throw exception;
            }
            if (values != null
                    && !parameter.acceptedValues().isEmpty()
                    && !parameter.acceptedValues().contains(values[0])) {
                ApiRequestException exception =
                        new ApiRequestException(
                                ProblemCode.FILTER_INVALID, "/query/" + parameter.name());
                throw exception;
            }
        }
        return true;
    }

    /** The public catalogue reads declare a closed query; other operations are unaffected. */
    private static boolean isClosedQueryOperation(HandlerMethod method) {
        Class<?> beanType = method.getBeanType();
        return ReleasesApi.class.isAssignableFrom(beanType)
                || CatalogueApi.class.isAssignableFrom(beanType);
    }

    private static Set<QueryParameter> queryParameters(HandlerMethod method) {
        return Arrays.stream(method.getMethodParameters())
                .map(StrictQueryParameterInterceptor::queryParameter)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** A repeated value is reported against the parameter it actually belongs to. */
    private static ProblemCode repeatedParameterCode(QueryParameter parameter) {
        if (parameter.pagination()) {
            return ProblemCode.PAGINATION_INVALID;
        }
        return SEARCH_PARAMETER.equals(parameter.name())
                ? ProblemCode.SEARCH_QUERY_INVALID
                : ProblemCode.FILTER_INVALID;
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
