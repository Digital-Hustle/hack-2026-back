package ru.cashflow.authprofile.exception;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import ru.cashflow.authprofile.api.dto.response.ApiProblemRs;
import ru.cashflow.authprofile.api.dto.response.FieldErrorRs;
import ru.cashflow.authprofile.api.error.ErrorCode;

import java.io.IOException;
import java.net.URI;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ProblemWriter {

    private final ObjectMapper mapper;
    private final ExceptionResponseProvider exceptions;

    public ApiProblemRs problem(
            HttpServletRequest request, int status, ErrorCode code, List<FieldErrorRs> errors) {
        return ApiProblemRs.builder()
                .type(URI.create("about:blank"))
                .title(HttpStatus.valueOf(status).getReasonPhrase())
                .status(status)
                .detail(code.name())
                .instance(URI.create(request.getRequestURI()))
                .code(code)
                .traceId(String.valueOf(request.getAttribute("traceId")))
                .errors(errors)
                .build();
    }

    public void write(
            HttpServletRequest request, HttpServletResponse response, RuntimeException exception)
            throws IOException {
        var descriptor = exceptions.describe(exception);
        response.setStatus(descriptor.status());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if (descriptor.retryAfter() != null) {
            response.setHeader(HttpHeaders.RETRY_AFTER, descriptor.retryAfter().toString());
        }
        mapper.writeValue(
                response.getOutputStream(),
                problem(request, descriptor.status(), descriptor.code(), List.of()));
    }
}
