package com.petproject.payflow.api.error;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(exception.getStatus(), exception.getMessage());
        problem.setTitle(exception.getTitle());
        problem.setType(URI.create(exception.getErrorType()));
        problem.setInstance(URI.create(request.getRequestURI()));
        exception.getProperties().forEach(problem::setProperty);
        return ResponseEntity.status(exception.getStatus()).body(enrich(problem));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
                                                          HttpServletRequest request) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "urn:payflow:error:validation",
                "Validation failed", "Request body contains invalid fields", request);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(enrich(problem));
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ProblemDetail> handleMethodValidation(HandlerMethodValidationException exception,
                                                                HttpServletRequest request) {
        List<String> messages = exception.getAllErrors().stream()
                .map(error -> error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage())
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "urn:payflow:error:validation",
                "Validation failed", "Request contains invalid parameters", request);
        problem.setProperty("errors", messages);
        return ResponseEntity.badRequest().body(enrich(problem));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadable(HttpMessageNotReadableException exception,
                                                          HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "urn:payflow:error:malformed-request",
                "Malformed request", "Request body is missing or is not valid JSON", request);
        return ResponseEntity.badRequest().body(enrich(problem));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception,
                                                            HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "urn:payflow:error:invalid-parameter",
                "Invalid parameter", "Parameter '%s' has an invalid value".formatted(exception.getName()), request);
        return ResponseEntity.badRequest().body(enrich(problem));
    }

    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ProblemDetail> handleBinding(ServletRequestBindingException exception,
                                                       HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "urn:payflow:error:missing-parameter",
                "Missing request parameter", exception.getMessage(), request);
        return ResponseEntity.badRequest().body(enrich(problem));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException exception,
                                                             HttpServletRequest request) {
        log.warn("Database constraint violation on {}: {}", request.getRequestURI(), exception.getMostSpecificCause().getMessage());
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "urn:payflow:error:data-conflict",
                "Data conflict", "The request conflicts with existing data", request);
        return ResponseEntity.status(HttpStatus.CONFLICT).body(enrich(problem));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), exception);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR, "urn:payflow:error:internal",
                "Internal server error", "Unexpected error, please contact support", request);
        return ResponseEntity.internalServerError().body(enrich(problem));
    }

    private ProblemDetail problem(HttpStatus status, String type, String title, String detail,
                                  HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(type));
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }

    private ProblemDetail enrich(ProblemDetail problem) {
        problem.setProperty("timestamp", Instant.now());
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }

    private Map<String, String> describe(FieldError fieldError) {
        return Map.of(
                "field", fieldError.getField(),
                "message", fieldError.getDefaultMessage() == null ? "invalid value" : fieldError.getDefaultMessage());
    }
}
