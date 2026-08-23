package com.videogameplatform.api.delivery;

import com.videogameplatform.api.generated.model.ErrorCategory;
import com.videogameplatform.api.generated.model.Problem;
import com.videogameplatform.api.generated.model.ProblemCode;
import com.videogameplatform.api.generated.model.Violation;
import com.videogameplatform.catalogue.application.CatalogueNotReadyException;
import com.videogameplatform.catalogue.application.CatalogueReadException;
import com.videogameplatform.catalogue.application.ReleaseQueryValidationException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
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

    @ExceptionHandler(ApiRequestException.class)
    ResponseEntity<Problem> requestInvalid(ApiRequestException exception) {
        return switch (exception.code()) {
            case "FILTER_INVALID" ->
                    problem(
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            exception.code(),
                            "Release filter is invalid",
                            "Correct the release view or filter combination.",
                            "validation",
                            exception.pointer(),
                            "Use exactly one supported release view or filter value.");
            case "PAGINATION_INVALID" ->
                    problem(
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            exception.code(),
                            "Pagination is invalid",
                            "Use page at least 1 and pageSize from 1 through 100.",
                            "validation",
                            exception.pointer(),
                            "Use an integer inside the supported pagination range.");
            case "REQUEST_PARAMETER_UNKNOWN" ->
                    problem(
                            HttpStatus.UNPROCESSABLE_CONTENT,
                            exception.code(),
                            "Request parameter is unknown",
                            "Remove unsupported query parameters.",
                            "validation",
                            exception.pointer(),
                            "This query parameter is not supported.");
            default -> internalError();
        };
    }

    @ExceptionHandler(ReleaseQueryValidationException.class)
    ResponseEntity<Problem> taxonomyUnsupported(ReleaseQueryValidationException exception) {
        boolean platform =
                exception.code() == ReleaseQueryValidationException.Code.PLATFORM_NOT_SUPPORTED;
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                exception.code().name(),
                platform ? "Platform is not supported" : "Region is not supported",
                platform
                        ? "Select a platform from the normalized available filters."
                        : "Select a region from the normalized available filters.",
                "validation",
                platform ? "/query/platformId" : "/query/regionId",
                platform
                        ? "Use a supported platform identifier."
                        : "Use a supported region identifier.");
    }

    @ExceptionHandler(CatalogueNotReadyException.class)
    ResponseEntity<Problem> catalogueNotReady() {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CATALOGUE_NOT_READY",
                "Catalogue is not ready",
                "No valid local catalogue snapshot has been published yet.",
                "technical",
                "/catalogue",
                "Publish a valid local catalogue snapshot before retrying.");
    }

    @ExceptionHandler(CatalogueReadException.class)
    ResponseEntity<Problem> catalogueReadFailed() {
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "CATALOGUE_READ_FAILED",
                "Catalogue read failed",
                "Local catalogue data cannot currently be read.",
                "technical",
                "/catalogue",
                "Retry after local catalogue access is restored.");
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    ResponseEntity<Problem> representationNotAcceptable() {
        return problem(
                HttpStatus.NOT_ACCEPTABLE,
                "REPRESENTATION_NOT_ACCEPTABLE",
                "Representation is not acceptable",
                "Request application/json for this resource.",
                "validation",
                "/headers/Accept",
                "Use application/json in the Accept header.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<Problem> methodNotAllowed(HttpRequestMethodNotSupportedException exception) {
        ResponseEntity<Problem> response =
                problem(
                        HttpStatus.METHOD_NOT_ALLOWED,
                        "METHOD_NOT_ALLOWED",
                        "Method is not allowed",
                        "Use a supported method for this resource.",
                        "validation",
                        "/method",
                        "This HTTP method is not supported.");
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        if (exception.getSupportedHttpMethods() != null) {
            headers.setAllow(exception.getSupportedHttpMethods());
        }
        return new ResponseEntity<>(response.getBody(), headers, response.getStatusCode());
    }

    @ExceptionHandler({
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<Problem> requestMalformed() {
        return problem(
                HttpStatus.BAD_REQUEST,
                "REQUEST_MALFORMED",
                "Request is malformed",
                "A required parameter is missing or cannot be parsed.",
                "validation",
                "/query",
                "Use parameter names and primitive values defined by the API contract.");
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    ResponseEntity<Problem> requestConstraintInvalid(HandlerMethodValidationException exception) {
        String parameter =
                exception.getParameterValidationResults().stream()
                        .map(result -> result.getMethodParameter().getParameterName())
                        .filter(java.util.Objects::nonNull)
                        .findFirst()
                        .orElse("query");
        boolean pagination = "page".equals(parameter) || "pageSize".equals(parameter);
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                pagination ? "PAGINATION_INVALID" : "FILTER_INVALID",
                pagination ? "Pagination is invalid" : "Release filter is invalid",
                pagination
                        ? "Use page at least 1 and pageSize from 1 through 100."
                        : "Correct the release view or filter combination.",
                "validation",
                "/query/" + parameter,
                pagination
                        ? "Use an integer inside the supported pagination range."
                        : "Use a value accepted by the API contract.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<Void> resourceNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Problem> unexpectedFailure() {
        return internalError();
    }

    private static ResponseEntity<Problem> internalError() {
        return problem(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "Internal error",
                "The request could not be completed.",
                "technical",
                "/request",
                "The request could not be completed safely.");
    }

    private static ResponseEntity<Problem> problem(
            HttpStatus status,
            String code,
            String title,
            String detail,
            String category,
            String pointer,
            String message) {
        String correlationId = correlationId();
        String type =
                "urn:videogame-platform:problem:" + code.toLowerCase(Locale.ROOT).replace('_', '-');
        ProblemCode problemCode = ProblemCode.fromValue(code);
        Problem body =
                new Problem(
                        type,
                        title,
                        status.value(),
                        detail,
                        "urn:videogame-platform:problem-instance:" + correlationId,
                        problemCode,
                        ErrorCategory.fromValue(category),
                        correlationId);
        body.setViolations(List.of(new Violation(pointer, problemCode, message)));
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private static String correlationId() {
        String value = MDC.get("correlationId");
        return value == null ? UUID.randomUUID().toString() : value;
    }
}
