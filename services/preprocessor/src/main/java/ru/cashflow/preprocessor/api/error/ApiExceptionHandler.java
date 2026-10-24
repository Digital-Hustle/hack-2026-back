package ru.cashflow.preprocessor.api.error;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import ru.cashflow.preprocessor.api.error.ApiException.FieldError;
import ru.cashflow.preprocessor.features.statements.model.CsvInputException;
import ru.cashflow.preprocessor.integrations.orchestrator.OrchestratorProblemException;
import ru.cashflow.preprocessor.integrations.orchestrator.OrchestratorUnavailableException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CsvInputException.class)
    ResponseEntity<ProblemDetail> csv(CsvInputException exception) {
        return internalProblem(exception.status(), exception.code(), exception.getMessage(), exception.sourceLine());
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ProblemDetail> api(ApiException exception) {
        return importProblem(exception.status(), exception.code(), exception.getMessage(), exception.errors());
    }

    @ExceptionHandler(OrchestratorProblemException.class)
    ResponseEntity<ProblemDetail> orchestrator(OrchestratorProblemException exception) {
        LOG.warn("import_rejected_by_orchestrator status={} code={}", exception.status(), exception.code());
        var response = importProblem(exception.status(), exception.code(), exception.getMessage(), List.of());
        if (exception.details() == null || !exception.details().isObject()) {
            return response;
        }
        var upstream = exception.details();
        var problem = response.getBody();
        if (upstream.path("errors").isArray()) {
            problem.setProperty("errors", upstream.path("errors"));
        }
        if (upstream.path("retryAfterSec").canConvertToInt()
                && upstream.path("retryAfterSec").asInt() > 0) {
            problem.setProperty("retryAfterSec", upstream.path("retryAfterSec").asInt());
        }
        if (upstream.path("resourceId").isTextual()) {
            problem.setProperty("resourceId", upstream.path("resourceId").asText());
        }
        if (upstream.path("traceId").isTextual()) {
            problem.setProperty("traceId", upstream.path("traceId").asText());
        }
        return ResponseEntity.status(response.getStatusCode()).contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(OrchestratorUnavailableException.class)
    ResponseEntity<ProblemDetail> unavailable(OrchestratorUnavailableException exception) {
        LOG.warn("import_orchestrator_unavailable reason={}", exception.getMessage());
        return importProblem(503, "DEPENDENCY_UNAVAILABLE", "Orchestrator недоступен или не ответил вовремя",
                List.of());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> size() {
        return importProblem(413, "PAYLOAD_TOO_LARGE", "Загрузка превышает допустимый размер", List.of());
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ProblemDetail> missing() {
        return internalProblem(400, "FILE_REQUIRED", "Нужно передать CSV в multipart-поле file", 0);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<ProblemDetail> malformed() {
        return importProblem(400, "MALFORMED_REQUEST", "Некорректный формат запроса", List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> media() {
        return importProblem(415, "UNSUPPORTED_MEDIA_TYPE", "Ожидается multipart/form-data с CSV-файлом", List.of());
    }

    private ResponseEntity<ProblemDetail> internalProblem(int status, String code, String message, long line) {
        LOG.warn("statement_preparation_rejected code={} status={} sourceLine={}", code, status, line);
        var problem = base(status, code, message, List.of());
        problem.setProperty("sourceLine", line);
        problem.setProperty("requestId", MDC.get("requestId"));
        return entity(problem);
    }

    private ResponseEntity<ProblemDetail> importProblem(int status, String code, String message,
                                                        List<FieldError> errors) {
        var problem = base(status, code, message, errors);
        return entity(problem);
    }

    private ProblemDetail base(int status, String code, String message, List<FieldError> errors) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), truncate(message));
        problem.setTitle(code);
        problem.setProperty("code", code);
        problem.setProperty("traceId", MDC.get("requestId"));
        problem.setProperty("errors", errors.stream()
                .map(error -> Map.of("field", error.field(), "code", error.code(),
                        "message", truncate(error.message())))
                .toList());
        problem.setProperty("retryAfterSec", null);
        problem.setProperty("resourceId", null);
        return problem;
    }

    private ResponseEntity<ProblemDetail> entity(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private static String truncate(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
