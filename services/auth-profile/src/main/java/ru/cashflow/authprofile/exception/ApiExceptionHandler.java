package ru.cashflow.authprofile.exception;

import jakarta.servlet.http.HttpServletRequest;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import ru.cashflow.authprofile.api.cookie.AuthCookieManager;
import ru.cashflow.authprofile.api.dto.response.ApiProblemRs;
import ru.cashflow.authprofile.api.dto.response.FieldErrorRs;
import ru.cashflow.authprofile.api.error.ErrorCode;
import ru.cashflow.authprofile.features.security.exceptions.InvalidRefreshException;
import ru.cashflow.authprofile.features.security.exceptions.RefreshReusedException;

import java.util.List;

@RestControllerAdvice
@RequiredArgsConstructor
public class ApiExceptionHandler {

    private final ProblemWriter problems;
    private final AuthCookieManager cookies;
    private final ExceptionResponseProvider exceptions;

    @ExceptionHandler({DomainException.class, SystemException.class})
    ResponseEntity<ApiProblemRs> auth(RuntimeException e, HttpServletRequest request) {
        var descriptor = exceptions.describe(e);
        var builder = ResponseEntity.status(descriptor.status());

        if (e instanceof InvalidRefreshException || e instanceof RefreshReusedException) {
            var headers = new HttpHeaders();
            cookies.clear(headers, false);
            builder.headers(headers);
        }

        if (descriptor.retryAfter() != null) {
            builder.header(HttpHeaders.RETRY_AFTER, descriptor.retryAfter().toString());
        }

        return builder.contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .body(problems.problem(request, descriptor.status(), descriptor.code(), List.of()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiProblemRs> validation(
            MethodArgumentNotValidException e, HttpServletRequest request) {
        var errors =
                e.getBindingResult().getFieldErrors().stream()
                        .map(
                                f ->
                                        FieldErrorRs.builder()
                                                .field(f.getField())
                                                .code(f.getCode())
                                                .message("Invalid field")
                                                .build())
                        .toList();

        return response(
                request, HttpStatus.BAD_REQUEST.value(), ErrorCode.VALIDATION_ERROR, errors);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    ResponseEntity<ApiProblemRs> badRequest(Exception e, HttpServletRequest request) {
        return response(
                request, HttpStatus.BAD_REQUEST.value(), ErrorCode.VALIDATION_ERROR, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiProblemRs> notFound(Exception e, HttpServletRequest request) {
        return response(request, HttpStatus.NOT_FOUND.value(), ErrorCode.NOT_FOUND, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiProblemRs> method(Exception e, HttpServletRequest request) {
        return response(
                request,
                HttpStatus.METHOD_NOT_ALLOWED.value(),
                ErrorCode.METHOD_NOT_ALLOWED,
                List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiProblemRs> media(Exception e, HttpServletRequest request) {
        return response(
                request,
                HttpStatus.UNSUPPORTED_MEDIA_TYPE.value(),
                ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                List.of());
    }

    @ExceptionHandler({DataAccessException.class, org.jooq.exception.DataAccessException.class})
    ResponseEntity<ApiProblemRs> database(Exception e, HttpServletRequest request) {
        return response(
                request,
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                ErrorCode.DEPENDENCY_UNAVAILABLE,
                List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiProblemRs> unexpected(Exception e, HttpServletRequest request) {
        // Do not log exception messages or request bodies: JDBC/SMTP can include personal data.
        return response(
                request,
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                ErrorCode.INTERNAL_ERROR,
                List.of());
    }

    private ResponseEntity<ApiProblemRs> response(
            HttpServletRequest request, int status, ErrorCode code, List<FieldErrorRs> errors) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .cacheControl(CacheControl.noStore())
                .body(problems.problem(request, status, code, errors));
    }
}
