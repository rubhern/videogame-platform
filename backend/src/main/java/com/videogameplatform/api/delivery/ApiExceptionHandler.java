package com.videogameplatform.api.delivery;

import com.videogameplatform.api.generated.model.ErrorCategory;
import com.videogameplatform.api.generated.model.Problem;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.api.generated.model.ReleaseView;
import com.videogameplatform.api.generated.model.Violation;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.releases.ReleaseQueryValidationException;
import com.videogameplatform.catalogue.application.search.SearchQueryInvalidException;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps delivery and catalogue failures to the reviewed stable Problem Details contract. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-ID";
    private static final String CORRELATION_ID_NAME = "correlationId";
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiRequestException.class)
    public ResponseEntity<Problem> requestInvalid(
            ApiRequestException exception, HttpServletResponse response) {
        return switch (exception.code()) {
            case FILTER_INVALID ->
                    problem(
                            response,
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            exception.code(),
                            "Release filter is invalid",
                            "Correct the release view or filter combination.",
                            ErrorCategory.VALIDATION,
                            exception.pointer(),
                            "Use exactly one supported release view or filter value.");
            case PAGINATION_INVALID ->
                    problem(
                            response,
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            exception.code(),
                            "Pagination is invalid",
                            "Use page at least 1 and pageSize from 1 through 100.",
                            ErrorCategory.VALIDATION,
                            exception.pointer(),
                            "Use an integer inside the supported pagination range.");
            case SEARCH_QUERY_INVALID -> searchQueryInvalid(response, exception.pointer());
            case REQUEST_PARAMETER_UNKNOWN ->
                    problem(
                            response,
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            exception.code(),
                            "Request parameter is unknown",
                            "Remove unsupported query parameters.",
                            ErrorCategory.VALIDATION,
                            exception.pointer(),
                            "This query parameter is not supported.");
            default -> unexpectedFailure(exception, response);
        };
    }

    @ExceptionHandler(ReleaseQueryValidationException.class)
    ResponseEntity<Problem> taxonomyUnsupported(
            ReleaseQueryValidationException exception, HttpServletResponse response) {
        return switch (exception.code()) {
            case PLATFORM_NOT_SUPPORTED ->
                    problem(
                            response,
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            ProblemCode.PLATFORM_NOT_SUPPORTED,
                            "Platform is not supported",
                            "Select a platform from the normalized available filters.",
                            ErrorCategory.VALIDATION,
                            "/query/platformId",
                            "Use a supported platform identifier.");
            case REGION_NOT_SUPPORTED ->
                    problem(
                            response,
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            ProblemCode.REGION_NOT_SUPPORTED,
                            "Region is not supported",
                            "Select a region from the normalized available filters.",
                            ErrorCategory.VALIDATION,
                            "/query/regionId",
                            "Use a supported region identifier.");
        };
    }

    @ExceptionHandler(SearchQueryInvalidException.class)
    ResponseEntity<Problem> searchQueryInvalid(HttpServletResponse response) {
        return searchQueryInvalid(response, "/query/q");
    }

    @ExceptionHandler(CatalogueNotReadyException.class)
    ResponseEntity<Problem> catalogueNotReady(HttpServletResponse response) {
        return problem(
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemCode.CATALOGUE_NOT_READY,
                "Catalogue is not ready",
                "No valid local catalogue snapshot has been published yet.",
                ErrorCategory.TECHNICAL,
                "/catalogue",
                "Publish a valid local catalogue snapshot before retrying.");
    }

    @ExceptionHandler(CatalogueReadException.class)
    ResponseEntity<Problem> catalogueReadFailed(
            CatalogueReadException exception, HttpServletResponse response) {
        logTechnicalFailure(ProblemCode.CATALOGUE_READ_FAILED, exception);
        return problem(
                response,
                HttpStatus.SERVICE_UNAVAILABLE,
                ProblemCode.CATALOGUE_READ_FAILED,
                "Catalogue read failed",
                "Local catalogue data cannot currently be read.",
                ErrorCategory.TECHNICAL,
                "/catalogue",
                "Retry after local catalogue access is restored.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Problem> representationNotAcceptable(HttpServletResponse response) {
        return problem(
                response,
                HttpStatus.NOT_ACCEPTABLE,
                ProblemCode.REPRESENTATION_NOT_ACCEPTABLE,
                "Representation is not acceptable",
                "Request application/json for this resource.",
                ErrorCategory.VALIDATION,
                "/headers/Accept",
                "Use application/json in the Accept header.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Problem> methodNotAllowed(
            HttpRequestMethodNotSupportedException exception, HttpServletResponse servletResponse) {
        ResponseEntity<Problem> response =
                problem(
                        servletResponse,
                        HttpStatus.METHOD_NOT_ALLOWED,
                        ProblemCode.METHOD_NOT_ALLOWED,
                        "Method is not allowed",
                        "Use a supported method for this resource.",
                        ErrorCategory.VALIDATION,
                        "/method",
                        "This HTTP method is not supported.");
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        if (exception.getSupportedHttpMethods() != null) {
            headers.setAllow(exception.getSupportedHttpMethods());
        }
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<Problem> requestMalformed(HttpServletResponse response) {
        return problem(
                response,
                HttpStatus.BAD_REQUEST,
                ProblemCode.REQUEST_MALFORMED,
                "Request is malformed",
                "A required parameter is missing or cannot be parsed.",
                ErrorCategory.VALIDATION,
                "/query",
                "Use parameter names and primitive values defined by the API contract.");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<Problem> requestValueInvalid(
            MethodArgumentTypeMismatchException exception, HttpServletResponse response) {
        if (ReleaseView.class.equals(exception.getRequiredType())) {
            return problem(
                    response,
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    ProblemCode.FILTER_INVALID,
                    "Release filter is invalid",
                    "Correct the release view or filter combination.",
                    ErrorCategory.VALIDATION,
                    "/query/" + exception.getName(),
                    "Use exactly one supported release view or filter value.");
        }
        return requestMalformed(response);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Problem> requestConstraintInvalid(
            HandlerMethodValidationException exception, HttpServletResponse response) {
        String parameter =
                exception.getParameterValidationResults().stream()
                        .map(result -> result.getMethodParameter().getParameterName())
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse("query");
        if ("q".equals(parameter)) {
            return searchQueryInvalid(response, "/query/q");
        }
        boolean pagination = "page".equals(parameter) || "pageSize".equals(parameter);
        return problem(
                response,
                HttpStatus.UNPROCESSABLE_CONTENT,
                pagination ? ProblemCode.PAGINATION_INVALID : ProblemCode.FILTER_INVALID,
                pagination ? "Pagination is invalid" : "Release filter is invalid",
                pagination
                        ? "Use page at least 1 and pageSize from 1 through 100."
                        : "Correct the release view or filter combination.",
                ErrorCategory.VALIDATION,
                "/query/" + parameter,
                pagination
                        ? "Use an integer inside the supported pagination range."
                        : "Use a value accepted by the API contract.");
    }

    @ExceptionHandler({NoResourceFoundException.class, ApiOperationNotDeliveredException.class})
    ResponseEntity<Void> resourceNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unexpectedFailure(Exception exception, HttpServletResponse response) {
        logTechnicalFailure(ProblemCode.INTERNAL_ERROR, exception);
        return internalError(response);
    }

    private static ResponseEntity<Problem> searchQueryInvalid(
            HttpServletResponse response, String pointer) {
        return problem(
                response,
                HttpStatus.UNPROCESSABLE_CONTENT,
                ProblemCode.SEARCH_QUERY_INVALID,
                "Search query is invalid",
                "Supply a non-blank query of at most 100 Unicode code points.",
                ErrorCategory.VALIDATION,
                pointer,
                "Use a single searchable query inside the supported bounds.");
    }

    private static ResponseEntity<Problem> internalError(HttpServletResponse response) {
        return problem(
                response,
                HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemCode.INTERNAL_ERROR,
                "Internal error",
                "The request could not be completed.",
                ErrorCategory.TECHNICAL,
                "/request",
                "The request could not be completed safely.");
    }

    private static ResponseEntity<Problem> problem(
            HttpServletResponse response,
            HttpStatus status,
            ProblemCode code,
            String title,
            String detail,
            ErrorCategory category,
            String pointer,
            String message) {
        String correlationId = correlationId(response);
        String type =
                "urn:videogame-platform:problem:"
                        + code.getValue().toLowerCase(Locale.ROOT).replace('_', '-');
        Problem body =
                new Problem(
                        type,
                        title,
                        status.value(),
                        detail,
                        "urn:videogame-platform:problem-instance:" + correlationId,
                        code,
                        category,
                        correlationId);
        body.setViolations(List.of(new Violation(pointer, code, message)));
        return ResponseEntity.status(status)
                .header(CORRELATION_ID_HEADER, correlationId)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private static String correlationId(HttpServletResponse response) {
        String responseCorrelationId = response.getHeader(CORRELATION_ID_HEADER);
        if (responseCorrelationId != null && !responseCorrelationId.isBlank()) {
            return responseCorrelationId;
        }
        String mdcCorrelationId = MDC.get(CORRELATION_ID_NAME);
        String effectiveCorrelationId =
                mdcCorrelationId == null ? UUID.randomUUID().toString() : mdcCorrelationId;
        response.setHeader(CORRELATION_ID_HEADER, effectiveCorrelationId);
        return effectiveCorrelationId;
    }

    private static void logTechnicalFailure(ProblemCode code, Exception exception) {
        LOGGER.atError()
                .addKeyValue("error.code", code.getValue())
                .setCause(exception)
                .log("API request failed");
    }
}
