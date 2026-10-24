package ru.cashflow.forecast.api.error;

import java.util.List;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import ru.cashflow.forecast.features.shared.model.InputException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(InputException.class)
    public ResponseEntity<ProblemDetail> input(InputException exception, HttpServletRequest request) {
        int status = exception.code().equals("CONFLICT") ? 409 : 422;
        return response(status, exception.code(), exception.getMessage(), List.of(new FieldErrorRs(exception.field(), exception.code(), exception.getMessage())), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var errors = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorRs(error.getField(), "INVALID_VALUE", "Field violates the input contract"))
                .distinct().limit(100).toList();
        return response(422, "VALIDATION_FAILED", "Request violates the input contract", errors, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> unreadable(HttpMessageNotReadableException exception, HttpServletRequest request) {
        Throwable cause = exception;
        boolean syntax = false;
        String field = "body";
        while (cause != null) {
            syntax |= cause instanceof JsonParseException;
            if (cause instanceof JsonMappingException mapping && !mapping.getPath().isEmpty()) {
                field = mapping.getPath().stream().map(reference -> reference.getFieldName() == null
                        ? "[" + reference.getIndex() + "]" : safeField(reference.getFieldName())).collect(java.util.stream.Collectors.joining("."));
            }
            cause = cause.getCause();
        }
        int status = syntax ? 400 : 422;
        String code = syntax ? "MALFORMED_REQUEST" : "VALIDATION_FAILED";
        return response(status, code, "Invalid JSON syntax or structure", List.of(new FieldErrorRs(field, code, "Invalid JSON value")), request);
    }

    @ExceptionHandler({MissingRequestHeaderException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> headers(Exception exception, HttpServletRequest request) {
        return response(400, "MALFORMED_REQUEST", "Required UUID headers are missing or invalid", List.of(), request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ProblemDetail> media(Exception exception, HttpServletRequest request) {
        return response(415, "UNSUPPORTED_MEDIA_TYPE", "Use application/json", List.of(), request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(Exception exception, HttpServletRequest request) {
        return response(404, "NOT_FOUND", "No resource on this route", List.of(), request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ProblemDetail> method(Exception exception, HttpServletRequest request) {
        return response(405, "METHOD_NOT_ALLOWED", "HTTP method is not allowed", List.of(), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception exception, HttpServletRequest request) {
        return response(500, "INTERNAL_ERROR", "Calculation could not be completed", List.of(), request);
    }

    private ResponseEntity<ProblemDetail> response(int status, String code, String detail, List<FieldErrorRs> errors, HttpServletRequest request) {
        String traceId = (String) request.getAttribute("forecast.traceId");
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(Problems.create(status, code, detail, errors, traceId));
    }

    private String safeField(String name) {
        return name.matches("[A-Za-z][A-Za-z0-9_]{0,80}") ? name : "field";
    }
}
