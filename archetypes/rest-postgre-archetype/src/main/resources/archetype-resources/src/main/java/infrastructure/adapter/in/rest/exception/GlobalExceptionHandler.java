package ${package}.infrastructure.adapter.in.rest.exception;

import ${package}.domain.exception.${entity}NotFoundException;
import ${package}.infrastructure.adapter.in.rest.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.OffsetDateTime;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(${entity}NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(${entity}NotFoundException ex, HttpServletRequest request) {
        return buildErrorResponse(404, ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        var type = ex.getRequiredType();
        String message = String.format("Invalid value '%s' for parameter '%s'. Expected type: %s",
                ex.getValue(), ex.getName(), type != null ? type.getSimpleName() : "unknown");
        return buildErrorResponse(400, message, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleInternalServerError(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error at {}: {}", request.getRequestURI(), ex.toString(), ex);
        return buildErrorResponse(500, "Internal server error. Please contact support.", request);
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(int status, String message, HttpServletRequest request) {
        ErrorResponse error = new ErrorResponse(OffsetDateTime.now(), status, message);
        error.setPath(request.getRequestURI());
        return ResponseEntity.status(status).body(error);
    }
}
