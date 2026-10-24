package ru.cashflow.preprocessor.api.error;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import ru.cashflow.preprocessor.features.statements.model.CsvInputException;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(CsvInputException.class)
    ResponseEntity<ProblemDetail> csv(CsvInputException exception) {
        return problem(exception.status(), exception.code(), exception.getMessage(), exception.sourceLine());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ProblemDetail> size() {
        return problem(413, "FILE_TOO_LARGE", "Загрузка превышает допустимый размер", 0);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ProblemDetail> missing() {
        return problem(400, "FILE_REQUIRED", "Нужно передать CSV в multipart-поле file", 0);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> media() {
        return problem(415, "UNSUPPORTED_MEDIA_TYPE", "Ожидается multipart/form-data с CSV-файлом", 0);
    }

    private ResponseEntity<ProblemDetail> problem(int status, String code, String message, long line) {
        LOG.warn("statement_preparation_rejected code={} status={} sourceLine={}", code, status, line);
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), message);
        problem.setTitle(code);
        problem.setProperty("code", code);
        problem.setProperty("sourceLine", line);
        problem.setProperty("requestId", MDC.get("requestId"));
        return ResponseEntity.status(status).body(problem);
    }
}
